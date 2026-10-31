package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The marker JSON is written by hand to keep a serialiser out of the UI module, so its escaping, its
 * compact tuple shape and the dropping of unusable coordinates are worth pinning down.
 */
class LiveMapTest {

    @Test
    void writesOneTuplePerMarker() {
        var json = LiveMap.json(List.of(new LiveMap.Marker(52.5, 13.4, "10 · Trip 1", "Central", 120, 5, 42)));

        assertEquals("[[52.5,13.4,\"10 · Trip 1\",\"Central\",120,5,\"42\"]]", json);
    }

    @Test
    void writesTripUpdateIdAsAStringToSurviveJavaScriptNumbers() {
        var id = 8602430676343853057L;
        var json = LiveMap.json(List.of(new LiveMap.Marker(52.5, 13.4, "10 · Trip 1", "Central", 120, 5, id)));

        assertEquals("[[52.5,13.4,\"10 · Trip 1\",\"Central\",120,5,\"" + id + "\"]]", json);
    }

    @Test
    void roundsCoordinatesWithoutLosingMapPrecision() {
        var json = LiveMap.json(
                List.of(new LiveMap.Marker(52.12345678, 13.98765432, "10 · Trip 1", "Central", null, null, 42)));

        assertEquals("[[52.123457,13.987654,\"10 · Trip 1\",\"Central\",null,null,\"42\"]]", json);
    }

    @Test
    void escapesQuotesAndControlCharacters() {
        var json = LiveMap.json(List.of(new LiveMap.Marker(1, 2, "a\"b", "line\nbreak", null, null, 42)));

        assertFalse(json.contains("\"a\"b\""), "an unescaped quote would break the JSON array");
        assertEquals("[[1.0,2.0,\"a\\\"b\",\"line\\nbreak\",null,null,\"42\"]]", json);
    }

    @Test
    void dropsMarkersWithoutFiniteCoordinates() {
        var json = LiveMap.json(List.of(
                new LiveMap.Marker(Double.NaN, 13.4, "nan", "detail", null, null, 1),
                new LiveMap.Marker(52.5, Double.POSITIVE_INFINITY, "infinite", "detail", null, null, 2)));

        assertEquals("[]", json, "MapLibre cannot render, and JSON cannot express, non-finite coordinates");
    }

    @Test
    void exposesTheOpenTripLabel() {
        var map = new LiveMap();

        map.setOpenTripText("Open trip");

        assertEquals("Open trip", map.getElement().getProperty("openTripText"));
    }

    @Test
    void exposesTheTransportOverlayProperties() {
        var map = new LiveMap();

        map.setTransportOverlay("https://tile.example/{z}/{x}/{y}.png", 0.4);

        assertEquals("https://tile.example/{z}/{x}/{y}.png", map.getElement().getProperty("transportOverlayUrl"));
        assertEquals(
                0.4, Double.parseDouble(String.valueOf(map.getElement().getProperty("transportOverlayOpacity"))), 1e-9);
    }
}
