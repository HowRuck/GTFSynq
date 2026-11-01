package org.example.gtfsynq.shared.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Covers what the deduplication hash considers a change: a delay when the feed publishes one, and
 * otherwise the minute of the predicted time, so a time-only feed (HSL) is not treated as frozen.
 */
class TripStopTimeUpdateDtoTest {

    private static final long TRIP_KEY = 42L;

    private static final String FEED = "helsinki-hsl";

    private static final Instant FEED_TS = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void aTimeOnlyPredictionIsUnchangedWithinTheSameMinute() {
        assertEquals(hashOf("2026-11-01T12:00:10Z", null), hashOf("2026-11-01T12:00:50Z", null));
    }

    @Test
    void aTimeOnlyPredictionChangesWhenItMovesToAnotherMinute() {
        assertNotEquals(hashOf("2026-11-01T12:00:10Z", null), hashOf("2026-11-01T12:02:10Z", null));
    }

    @Test
    void aPublishedDelayKeepsTheTimeOutOfTheHash() {
        assertEquals(hashOf("2026-11-01T12:00:10Z", 120), hashOf("2026-11-01T13:30:10Z", 120));
    }

    @Test
    void delaysAreComparedOutsideTheHash() {
        assertEquals(
                hashOf("2026-11-01T12:00:10Z", 120),
                hashOf("2026-11-01T12:00:10Z", 300),
                "the hash leaves delays out on purpose; the sink compares them separately");
    }

    @Test
    void aMissingTimeAndDelayLeavesTheHashAlone() {
        assertEquals(hashOf(null, null), hashOf(null, null));
    }

    @Test
    void namingAStopDescribesTheStoredRow() {
        var row = update(Instant.parse("2026-11-01T12:00:10Z"), null);

        assertSame(row, row.withStopId("2413601"), "an unchanged stop id returns the same instance");

        var named = row.withStopId("2411601");

        assertEquals("2411601", named.stopId());
        assertNotEquals(row.hash(), named.hash(), "the stop id is part of the stored row");
        assertEquals(
                named.hash(),
                update(Instant.parse("2026-11-01T12:00:10Z"), null)
                        .withStopId("2411601")
                        .hash(),
                "the same row must hash the same regardless of which instance named it");
    }

    @Test
    void resolvingASequenceDescribesTheStoredRow() {
        var resolved = update(Instant.parse("2026-11-01T12:00:10Z"), null).withStopSequence(7);

        assertEquals(7, resolved.stopSequence());
        assertEquals(
                update(Instant.parse("2026-11-01T12:00:10Z"), null)
                        .withStopSequence(7)
                        .hash(),
                resolved.hash(),
                "the same row must hash the same regardless of which instance resolved it");
        assertNotEquals(
                resolved.hash(),
                update(Instant.parse("2026-11-01T12:00:10Z"), null)
                        .withStopSequence(8)
                        .hash(),
                "the sequence is part of the stored row");
    }

    private static long hashOf(String arrival, Integer delay) {
        return update(arrival == null ? null : Instant.parse(arrival), delay).hash();
    }

    private static TripStopTimeUpdateDto update(Instant arrival, Integer delay) {
        var event = StopTimeEvent.newBuilder();
        if (arrival != null) {
            event.setTime(arrival.getEpochSecond());
        }
        if (delay != null) {
            event.setDelay(delay);
        }
        var stopTimeUpdate = StopTimeUpdate.newBuilder()
                .setStopId("2413601")
                .setArrival(event)
                .build();
        return TripStopTimeUpdateDto.fromProto(TRIP_KEY, FEED, FEED_TS, stopTimeUpdate);
    }
}
