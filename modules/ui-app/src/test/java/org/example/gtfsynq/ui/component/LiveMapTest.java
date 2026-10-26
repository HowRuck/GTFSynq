package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The marker JSON is written by hand to keep a serialiser out of the UI module, so its escaping and
 * the dropping of unusable coordinates are worth pinning down.
 */
class LiveMapTest {

    @Test
    void writesOneObjectPerMarker() {
        var json = LiveMap.json(List.of(new LiveMap.Marker(52.5, 13.4, "10 · Trip 1", "Central", 120)));

        assertEquals(
                "[{\"lat\":52.5,\"lon\":13.4,\"label\":\"10 · Trip 1\",\"detail\":\"Central\",\"delaySeconds\":120}]",
                json);
    }

    @Test
    void escapesQuotesAndControlCharacters() {
        var json = LiveMap.json(List.of(new LiveMap.Marker(1, 2, "a\"b", "line\nbreak", null)));

        assertFalse(json.contains("\"a\"b\""), "an unescaped quote would break the JSON array");
        assertEquals(
                "[{\"lat\":1.0,\"lon\":2.0,\"label\":\"a\\\"b\",\"detail\":\"line\\nbreak\",\"delaySeconds\":null}]",
                json);
    }

    @Test
    void dropsMarkersWithoutFiniteCoordinates() {
        var json = LiveMap.json(List.of(
                new LiveMap.Marker(Double.NaN, 13.4, "nan", "detail", null),
                new LiveMap.Marker(52.5, Double.POSITIVE_INFINITY, "infinite", "detail", null)));

        assertEquals("[]", json, "MapLibre cannot render, and JSON cannot express, non-finite coordinates");
    }
}
