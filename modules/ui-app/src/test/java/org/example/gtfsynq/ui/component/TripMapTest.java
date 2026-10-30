package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TripMapTest {

    @Test
    void writesOneTuplePerStopWithItsProgressState() {
        var json = TripMap.json(List.of(
                new TripMap.Stop(52.5, 13.4, "Central", TripMap.StopState.REACHED),
                new TripMap.Stop(52.6, 13.5, "North", TripMap.StopState.CURRENT)));

        assertEquals("[[52.5,13.4,\"Central\",1],[52.6,13.5,\"North\",2]]", json);
    }

    @Test
    void dropsStopsWithoutFiniteCoordinates() {
        var json = TripMap.json(List.of(
                new TripMap.Stop(Double.NaN, 13.4, "nan", TripMap.StopState.UPCOMING),
                new TripMap.Stop(52.5, Double.POSITIVE_INFINITY, "infinite", TripMap.StopState.UPCOMING)));

        assertEquals("[]", json, "MapLibre cannot render, and JSON cannot express, non-finite coordinates");
    }

    @Test
    void defaultsToUpcomingWhenAStateIsMissing() {
        var json = TripMap.json(List.of(new TripMap.Stop(52.5, 13.4, "Central", null)));

        assertEquals("[[52.5,13.4,\"Central\",0]]", json);
    }
}
