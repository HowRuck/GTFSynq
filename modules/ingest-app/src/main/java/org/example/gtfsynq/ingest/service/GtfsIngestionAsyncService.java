package org.example.gtfsynq.ingest.service;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.example.gtfsynq.ingest.adapter.outbound.kafka.GtfsKafkaProducer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GtfsIngestionAsyncService {

    private final GtfsPollingService gtfsPollingService;
    private final GtfsKafkaProducer gtfsKafkaProducer;

    @Async
    public CompletableFuture<Void> processFeedUrlAsync(String feedId, String feedUrl) {
        var entities = gtfsPollingService.pollStream(feedId, feedUrl);

        if (entities == null || entities.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        gtfsKafkaProducer.sendTripUpdates(feedId, entities);

        return CompletableFuture.completedFuture(null);
    }
}
