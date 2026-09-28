package org.example.gtfsynq.ingest.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.config.GtfsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Service responsible for ingesting the static feeds of all configured sources at
 * regular intervals
 * <p>
 * Static feeds change on the order of days, not seconds, so they are polled on their own
 * schedule rather than on the GTFS-RT polling cadence. As the archives are large, a tick
 * that is still running when the next one fires is skipped entirely instead of queued
 */
@Service
@Slf4j
@AllArgsConstructor
@ConditionalOnProperty(prefix = "gtfsynq.static-polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GtfsStaticIngestionService {

    private final GtfsProperties gtfsConfig;
    private final GtfsStaticIngestionAsyncService ingestionAsyncService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Scheduled task to ingest the static feed of every source that has one configured.
     */
    @Scheduled(fixedRateString = "${gtfsynq.static-polling.interval-ms:86400000}")
    public void process() {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Previous static ingestion is still running, skipping this iteration");
            return;
        }

        var startTime = System.currentTimeMillis();

        try {
            var futures = gtfsConfig.sources().entrySet().stream()
                    .filter(entry -> entry.getValue().staticConfig() != null)
                    .filter(entry -> hasUrl(entry.getValue()))
                    .map(entry -> submitStaticFeed(
                            entry.getKey(), entry.getValue().staticConfig().url()))
                    .toList();

            if (futures.isEmpty()) {
                log.debug("No static feed sources configured, skipping static ingestion");
                return;
            }

            log.debug("Starting static ingestion for {} sources", futures.size());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.info("Static ingestion finished in {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("Critical error during static ingestion", e);
        } finally {
            isRunning.set(false);
        }
    }

    private static boolean hasUrl(GtfsProperties.FeedSource source) {
        var url = source.staticConfig().url();

        return url != null && !url.isBlank();
    }

    /**
     * Submits a single static feed for async processing, bounded by a timeout.
     *
     * @param feedId The ID of the feed
     * @param url    The static feed URL
     * @return a future that completes when the feed was processed or failed
     */
    private CompletableFuture<Void> submitStaticFeed(String feedId, String url) {
        return ingestionAsyncService
                .ingestAsync(feedId, url)
                .orTimeout(gtfsConfig.staticFeedTimeoutSeconds(), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("Static feed {} ({}) failed", feedId, url, ex);
                    return null;
                });
    }
}
