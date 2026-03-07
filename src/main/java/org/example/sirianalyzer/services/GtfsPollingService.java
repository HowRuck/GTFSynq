package org.example.sirianalyzer.services;

import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.util.SizeFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Service for polling a GTFS feed
 */
@Service
@Slf4j
public class GtfsPollingService {

    private final RestClient restClient;
    private final String feedUrl;

    /**
     * Creates a new GTFS polling service with the provided feed URL
     *
     * @param feedUrl The URL of the GTFS feed to poll
     */
    public GtfsPollingService(
            @Value("${gtfs.feed.url}") String feedUrl) {
        this.feedUrl = feedUrl;
        this.restClient = RestClient.create(feedUrl);
    }

    /**
     * Polls the GTFS feed and logs the size and fetch duration
     *
     * @return The GTFS feed bytes, or null if the feed is empty
     */
    public byte[] poll() {
        log.info("Polling GTFS feed from {}", feedUrl);

        var startTime = System.currentTimeMillis();

        try {
            var feedBytes = restClient.get()
                    .uri(feedUrl)
                    .retrieve()
                    .body(byte[].class);

            if (feedBytes == null || feedBytes.length == 0) {
                log.warn("Empty GTFS feed received");
                return null;
            }

            var fetchDuration = System.currentTimeMillis() - startTime;

            log.info("Received GTFS feed with {} in {} ms", SizeFormat.humanBytes(feedBytes.length), fetchDuration);

            return feedBytes;
        } catch (Exception e) {
            log.error("Failed to poll GTFS feed", e);
        }

        return null;
    }

}
