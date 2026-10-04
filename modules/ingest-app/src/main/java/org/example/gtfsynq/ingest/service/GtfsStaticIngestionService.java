package org.example.gtfsynq.ingest.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.ingest.config.GtfsProperties;
import org.example.gtfsynq.ingest.config.S3StorageProperties;
import org.example.gtfsynq.ingest.service.metrics.StaticFeedMetrics;
import org.example.gtfsynq.ingest.service.metrics.StaticFeedMetrics.Outcome;

/**
 * Service responsible for ingesting the static feeds of all configured sources at
 * regular intervals
 * <p>
 * Static feeds change on the order of days, not seconds, so they are polled on their own
 * schedule rather than on the GTFS-RT polling cadence. As the archives are large, a tick
 * that is still running when the next one fires is skipped entirely instead of queued
 */
@Singleton
@Slf4j
public class GtfsStaticIngestionService {

    private final GtfsProperties gtfsConfig;
    private final GtfsStaticIngestionAsyncService ingestionAsyncService;
    private final StaticFeedMetrics metrics;
    private final S3StorageProperties storageProperties;
    private final boolean staticPollingEnabled;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public GtfsStaticIngestionService(
            GtfsProperties gtfsConfig,
            GtfsStaticIngestionAsyncService ingestionAsyncService,
            StaticFeedMetrics metrics,
            S3StorageProperties storageProperties,
            @ConfigProperty(name = "gtfsynq.static-polling.enabled", defaultValue = "true")
                    boolean staticPollingEnabled) {
        this.gtfsConfig = gtfsConfig;
        this.ingestionAsyncService = ingestionAsyncService;
        this.metrics = metrics;
        this.storageProperties = storageProperties;
        this.staticPollingEnabled = staticPollingEnabled;
    }

    /**
     * Scheduled task to ingest the static feed of every source that has one configured.
     */
    @Scheduled(every = "{gtfsynq.static-polling.interval}")
    public void process() {
        if (!staticPollingEnabled || !storageProperties.enabled()) {
            log.debug("Static polling is disabled, skipping this iteration");
            return;
        }

        if (!isRunning.compareAndSet(false, true)) {
            metrics.recordCycleSkipped();
            log.info("Previous static ingestion is still running, skipping this iteration");
            return;
        }

        var startNanos = System.nanoTime();
        var startedAtMillis = System.currentTimeMillis();

        try {
            var futures = gtfsConfig.sources().entrySet().stream()
                    .filter(entry -> entry.getValue().staticConfig() != null)
                    .filter(entry -> hasUrl(entry.getValue()))
                    .map(entry -> submitStaticFeed(
                            entry.getKey(), entry.getValue().staticConfig().url()))
                    .toList();

            metrics.recordFeedsConfigured(futures.size());

            if (futures.isEmpty()) {
                log.debug("No static feed sources configured, skipping static ingestion");
                return;
            }

            log.debug("Starting static ingestion for {} sources", futures.size());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.info("Static ingestion finished in {}ms", System.currentTimeMillis() - startedAtMillis);
        } catch (Exception e) {
            log.error("Critical error during static ingestion", e);
        } finally {
            metrics.recordCycleDuration(System.nanoTime() - startNanos);
            isRunning.set(false);
        }
    }

    private static boolean hasUrl(GtfsProperties.FeedSource source) {
        var staticConfig = source.staticConfig();

        if (staticConfig == null) {
            return false;
        }

        var url = staticConfig.url();

        return url != null && !url.isBlank();
    }

    /**
     * Submits a single static feed for async processing, bounded by a timeout.
     * <p>
     * The timeout releases the cycle but does not interrupt the download, so a timed out
     * attempt is recorded here rather than left to the download's own — possibly never
     * arriving — outcome.
     *
     * @param feedId The ID of the feed
     * @param url    The static feed URL
     * @return a future that completes when the feed was processed, failed or timed out
     */
    private CompletableFuture<Void> submitStaticFeed(String feedId, String url) {
        return ingestionAsyncService
                .ingestAsync(feedId, url)
                .orTimeout(gtfsConfig.staticFeedTimeoutSeconds(), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    metrics.recordOutcome(feedId, isTimeout(ex) ? Outcome.TIMEOUT : Outcome.FAILED);
                    log.error("Static feed {} ({}) failed", feedId, url, ex);
                    return null;
                });
    }

    private static boolean isTimeout(Throwable ex) {
        return ex instanceof TimeoutException
                || ex instanceof CompletionException && ex.getCause() instanceof TimeoutException;
    }
}
