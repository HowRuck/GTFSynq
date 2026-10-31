package org.example.gtfsynq.ingest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedValidators;
import org.junit.jupiter.api.Test;

class StaticFeedValidatorCacheTest {

    private final StaticFeedValidatorCache cache = new StaticFeedValidatorCache();

    @Test
    void returnsEmptyValidatorsForAnUnknownFeed() {
        assertTrue(cache.get("gtfs-test", "https://example.com/gtfs.zip").isEmpty());
    }

    @Test
    void returnsValidatorsStoredForTheSameUrl() {
        cache.put("gtfs-test", "https://example.com/gtfs.zip", new StaticFeedValidators("\"v1\"", "date"));

        var validators = cache.get("gtfs-test", "https://example.com/gtfs.zip");

        assertEquals("\"v1\"", validators.etag());
        assertEquals("date", validators.lastModified());
    }

    @Test
    void ignoresValidatorsOfADifferentUrl() {
        cache.put("gtfs-test", "https://example.com/gtfs.zip", new StaticFeedValidators("\"v1\"", "date"));

        assertTrue(cache.get("gtfs-test", "https://example.com/new-gtfs.zip").isEmpty());
    }

    @Test
    void forgetsValidatorsOnDemand() {
        cache.put("gtfs-test", "https://example.com/gtfs.zip", new StaticFeedValidators("\"v1\"", "date"));

        cache.forget("gtfs-test");

        assertTrue(cache.get("gtfs-test", "https://example.com/gtfs.zip").isEmpty());
    }

    @Test
    void storingEmptyValidatorsForgetsTheEntry() {
        cache.put("gtfs-test", "https://example.com/gtfs.zip", new StaticFeedValidators("\"v1\"", "date"));

        cache.put("gtfs-test", "https://example.com/gtfs.zip", StaticFeedValidators.none());

        assertTrue(cache.get("gtfs-test", "https://example.com/gtfs.zip").isEmpty());
    }
}
