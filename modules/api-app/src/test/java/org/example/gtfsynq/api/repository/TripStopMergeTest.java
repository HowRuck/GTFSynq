package org.example.gtfsynq.api.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.example.gtfsynq.api.dto.StopTimeUpdateDto;
import org.junit.jupiter.api.Test;

class TripStopMergeTest {

    @Test
    void fillsStopCoordinatesFromTheStaticSchedule() {
        var schedule = stop(1, "Central", 52.52, 13.405, false);
        var live = stop(1, null, null, null, true);

        var merged = TripStopMerge.merge(List.of(schedule), List.of(live)).getFirst();

        assertEquals("Central", merged.stopName());
        assertEquals(52.52, merged.lat());
        assertEquals(13.405, merged.lon());
    }

    @Test
    void keepsLiveCoordinatesWhenTheFeedResolvesTheStop() {
        var schedule = stop(1, "Old name", 52.52, 13.405, false);
        var live = stop(1, "New name", 52.53, 13.41, true);

        var merged = TripStopMerge.merge(List.of(schedule), List.of(live)).getFirst();

        assertEquals("New name", merged.stopName());
        assertEquals(52.53, merged.lat());
        assertEquals(13.41, merged.lon());
    }

    @Test
    void leavesCoordinatesAbsentWhenNoStaticStopMatches() {
        var merged = TripStopMerge.merge(List.of(), List.of(stop(1, null, null, null, true)))
                .getFirst();

        assertNull(merged.lat());
        assertNull(merged.lon());
    }

    private static StopTimeUpdateDto stop(int sequence, String stopName, Double lat, Double lon, boolean realtime) {
        return new StopTimeUpdateDto(
                1,
                sequence,
                "stop-" + sequence,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                stopName,
                lat,
                lon,
                null,
                realtime);
    }
}
