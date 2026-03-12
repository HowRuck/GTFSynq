package org.example.sirianalyzer.services;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.transit.realtime.GtfsRealtime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Service
@Slf4j
public class GtfsParserService {

    private HashCode previousFeedHash;

    /**
     * Check if the feed has changed since the last fetch
     *
     * @param feedBytes The bytes of the GTFS feed
     * @return True if the feed has changed, false otherwise
     */
    private boolean feedChanged(byte[] feedBytes) {
        var currentHash = Hashing.murmur3_128()
                .hashBytes(feedBytes);

        if (currentHash.equals(previousFeedHash)) {
            return false;
        }

        previousFeedHash = currentHash;

        return true;
    }

    /**
     * Parse the GTFS feed into a FeedMessage
     *
     * @param feedBytes The bytes of the GTFS feed
     * @return The parsed FeedMessage, or null if parsing fails
     */
    public GtfsRealtime.FeedMessage parseGtfs(byte[] feedBytes) {
        var startTime = System.currentTimeMillis();

        if (feedBytes == null || feedBytes.length == 0) {
            log.warn("Empty GTFS feed received");
            return null;
        }

        if (!feedChanged(feedBytes)) {
            log.info("GTFS feed has not changed since last fetch");
            return null;
        }

        try (var is = new ByteArrayInputStream(feedBytes)) {
            var parsedFeed = GtfsRealtime.FeedMessage.parseFrom(is);

            var parseDuration = System.currentTimeMillis() - startTime;

            log.info("Parsed GTFS feed with {} entities in {} ms", parsedFeed.getEntityCount(), parseDuration);

            return parsedFeed;
        } catch (Exception e) {
            log.error("Failed to parse GTFS feed", e);
            return null;
        }

    }
}
