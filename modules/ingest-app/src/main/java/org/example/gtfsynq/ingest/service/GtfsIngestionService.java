package org.example.gtfsynq.ingest.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.config.GtfsProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Service responsible for processing GTFS feeds at regular intervals
 */
@Service
@Slf4j
@AllArgsConstructor
public class GtfsIngestionService {

    private final GtfsProperties gtfsConfig;
    private final GtfsIngestionAsyncService ingestionAsyncService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Scheduled task to process GTFS feeds at regular intervals.
     * <p>
     * If a previous tick is still running when the next interval fires, this tick
     * is skipped entirely (not queued). This is intentional: real-time GTFS data
     * is ephemeral, and backpressure via skip is preferred over falling further
     * behind on every tick.
     */
    @Scheduled(fixedRateString = "${gtfsynq.polling.interval-ms}")
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
                    .flatMap(e -> e.getValue().realtimeConfig().urls().stream().map(url -> submitFeed(e.getKey(), url)))
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
        return ingestionAsyncService
                .processFeedUrlAsync(feedId, url)
                .orTimeout(gtfsConfig.feedTimeoutSeconds(), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("Feed {} ({}) failed", feedId, url, ex);
                    return null;
                });
    }
}
