package org.example.gtfsynq.ingest.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.config.GtfsProperties;

/**
 * Service responsible for processing GTFS feeds at regular intervals
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsIngestionService {

    private final GtfsProperties gtfsConfig;
    private final GtfsIngestionAsyncService ingestionAsyncService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Scheduled task to process GTFS feeds at regular intervals.
     * <p>
     * If a previous tick is still running when the next interval fires, this tick
     * is skipped entirely (not queued). This is intentional: real-time GTFS data
     * is ephemeral, and backpressure via skip is preferred over falling further
     * behind on every tick.
     */
    @Scheduled(every = "{gtfsynq.polling.interval}")
    public void process() {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Previous polling is still running, skipping this iteration");
            return;
        }

        var startTime = System.currentTimeMillis();

        try {
            log.debug(
                    "Starting GTFS ingestion for {} sources",
                    gtfsConfig.sources().size());

            var futures = gtfsConfig.sources().entrySet().stream()
                    .flatMap(e -> {
                        var realtimeConfig = e.getValue().realtimeConfig();

                        if (realtimeConfig == null
                                || realtimeConfig.urls() == null
                                || realtimeConfig.urls().isEmpty()) {
                            return Stream.empty();
                        }

                        return realtimeConfig.urls().stream().map(url -> submitFeed(e.getKey(), url));
                    })
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.info("Total processing time: {}ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("Critical error during ingestion process", e);
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Submits a single feed URL for async processing, bounded by a timeout
     *
     * @param feedId The ID of the feed
     * @param url    The realtime feed URL
     * @return a future that completes when the feed was processed or failed
     */
    private CompletableFuture<Void> submitFeed(String feedId, String url) {
        var key = feedId + "\0" + url;

        if (!inFlight.add(key)) {
            log.warn("Feed {} ({}) is still processing from a previous poll, skipping this iteration", feedId, url);
            return CompletableFuture.completedFuture(null);
        }

        return ingestionAsyncService
                .processFeedUrlAsync(feedId, url)
                .orTimeout(gtfsConfig.feedTimeoutSeconds(), TimeUnit.SECONDS)
                .whenComplete((_, _) -> inFlight.remove(key))
                .exceptionally(ex -> {
                    log.error("Feed {} ({}) failed", feedId, url, ex);
                    return null;
                });
    }
}
