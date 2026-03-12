package org.example.sirianalyzer.services;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@AllArgsConstructor
public class GtfsIngestionService {

    private final GtfsPollingService gtfsPollingService;
    private final GtfsParserService gtfsParserService;
    private final GtfsProducerOrchestrator gtfsProducerOrchestrator;

    @Scheduled(fixedRateString = "${gtfs.fetch.interval-ms}")
    public void process() {
        var feedBytes = gtfsPollingService.poll();

        var feedMessage = gtfsParserService.parseGtfs(feedBytes);

        if (feedMessage == null) {
            return;
        }

        gtfsProducerOrchestrator.syncFeed(feedMessage.getEntityList());
    }
}
