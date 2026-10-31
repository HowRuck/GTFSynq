package org.example.gtfsynq.ingest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class GtfsPropertiesMappingTest {

    @Test
    void mapsAStaticOnlySource() {
        var properties = map("gtfs.sources.static-only.static-config.url", "https://example.com/gtfs.zip");

        var source = properties.sources().get("static-only");

        assertNotNull(source);
        assertEquals("https://example.com/gtfs.zip", source.staticConfig().url().orElseThrow());
        assertTrue(source.realtimeConfig().urls().isEmpty());
    }

    @Test
    void mapsARealtimeOnlySource() {
        var properties = map(
                "gtfs.sources.realtime-only.realtime-config.urls",
                "https://example.com/trip-updates,https://example.com/vehicle-positions");

        var source = properties.sources().get("realtime-only");

        assertNotNull(source);
        assertTrue(source.staticConfig().url().isEmpty());
        assertEquals(
                List.of("https://example.com/trip-updates", "https://example.com/vehicle-positions"),
                source.realtimeConfig().urls().orElseThrow());
    }

    private static GtfsProperties map(String key, String value) {
        var config = new SmallRyeConfigBuilder()
                .withMapping(GtfsProperties.class)
                .withDefaultValue(key, value)
                .build();

        return config.getConfigMapping(GtfsProperties.class);
    }
}
