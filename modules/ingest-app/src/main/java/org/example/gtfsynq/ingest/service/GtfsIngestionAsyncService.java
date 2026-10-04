package org.example.gtfsynq.ingest.service;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.outbound.kafka.GtfsKafkaProducer;

@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsIngestionAsyncService {

    private final GtfsPollingService gtfsPollingService;
    private final GtfsKafkaProducer gtfsKafkaProducer;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CompletableFuture<Void> processFeedUrlAsync(String feedId, String feedUrl) {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        var entities = gtfsPollingService.pollStream(feedId, feedUrl);

                        if (entities == null || entities.isEmpty()) {
                            return;
                        }
                        gtfsKafkaProducer.sendTripUpdates(feedId, entities);
                    } catch (Exception e) {
                        log.error("Unexpected error processing feed {} ({})", feedId, feedUrl, e);
                    }
                },
                executor);
    }

    void shutdown(@Observes ShutdownEvent event) {
        executor.shutdown();
    }
}
