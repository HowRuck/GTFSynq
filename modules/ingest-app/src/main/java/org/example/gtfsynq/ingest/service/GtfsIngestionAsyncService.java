package org.example.gtfsynq.ingest.service;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.outbound.kafka.GtfsKafkaProducer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsIngestionAsyncService {

    private final GtfsPollingService gtfsPollingService;
    private final GtfsKafkaProducer gtfsKafkaProducer;

    @Async
    public CompletableFuture<Void> processFeedUrlAsync(String feedId, String feedUrl) {
        try {
            var entities = gtfsPollingService.pollStream(feedId, feedUrl);

            if (entities == null || entities.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            gtfsKafkaProducer.sendTripUpdates(feedId, entities);
        } catch (Exception e) {
            log.error("Unexpected error processing feed {} ({})", feedId, feedUrl, e);
        }

        return CompletableFuture.completedFuture(null);
    }
}
