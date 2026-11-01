package org.example.gtfsynq.api.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
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

    @Test
    void derivesTheDelayOfATimeOnlyPrediction() {
        var scheduledArrival = OffsetDateTime.parse("2026-11-01T16:27:54+01:00");
        var scheduledDeparture = scheduledArrival.plusSeconds(30);
        var schedule = stopTimes(null, null, scheduledArrival, null, null, scheduledDeparture, false);
        var live = stopTimes(
                scheduledArrival.plusSeconds(180), null, null, scheduledDeparture.plusSeconds(180), null, null, true);

        var merged = TripStopMerge.merge(List.of(schedule), List.of(live)).getFirst();

        assertEquals(180, merged.arrivalDelay(), "a published time implies a delay against the plan");
        assertEquals(180, merged.departureDelay());
        assertEquals(scheduledArrival, merged.scheduledArrivalTime(), "the plan stays the plan");
    }

    @Test
    void keepsAPublishedDelayInsteadOfDerivingOne() {
        var scheduledArrival = OffsetDateTime.parse("2026-11-01T16:27:54+01:00");
        var schedule = stopTimes(null, null, scheduledArrival, null, null, scheduledArrival, false);
        var live = stopTimes(
                scheduledArrival.plusSeconds(180), 42, null, scheduledArrival.plusSeconds(180), 7, null, true);

        var merged = TripStopMerge.merge(List.of(schedule), List.of(live)).getFirst();

        assertEquals(42, merged.arrivalDelay());
        assertEquals(7, merged.departureDelay());
    }

    @Test
    void leavesTheDelayAbsentWithoutAPlanToCompare() {
        var predicted = OffsetDateTime.parse("2026-11-01T16:30:54+01:00");

        var merged = TripStopMerge.merge(
                        List.of(), List.of(stopTimes(predicted, null, null, predicted, null, null, true)))
                .getFirst();

        assertNull(merged.arrivalDelay());
        assertNull(merged.departureDelay());
    }

    private static StopTimeUpdateDto stopTimes(
            OffsetDateTime arrival,
            Integer arrivalDelay,
            OffsetDateTime scheduledArrival,
            OffsetDateTime departure,
            Integer departureDelay,
            OffsetDateTime scheduledDeparture,
            boolean realtime) {
        return new StopTimeUpdateDto(
                1,
                1,
                "stop-1",
                arrival,
                arrivalDelay,
                scheduledArrival,
                departure,
                departureDelay,
                scheduledDeparture,
                null,
                null,
                "Stop 1",
                null,
                null,
                null,
                realtime);
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
