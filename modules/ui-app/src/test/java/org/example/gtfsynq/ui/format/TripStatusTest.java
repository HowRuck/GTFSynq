package org.example.gtfsynq.ui.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.junit.jupiter.api.Test;

class TripStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private static Timestamp at(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).build();
    }

    private static TripUpdate.Builder seen(Instant lastSeen) {
        return TripUpdate.newBuilder().setLastSeenAt(at(lastSeen));
    }

    @Test
    void tripStartingLaterIsScheduled() {
        var trip = seen(NOW).setScheduledStartAt(at(NOW.plusSeconds(60))).build();
        assertEquals(TripStatus.SCHEDULED, TripStatus.of(trip, NOW));
    }

    @Test
    void tripPastItsLastStopIsFinishedEvenWhenStillReported() {
        var trip = seen(NOW)
                .setScheduledStartAt(at(NOW.minusSeconds(3600)))
                .setLastStopAt(at(NOW.minusSeconds(1)))
                .build();
        assertEquals(TripStatus.FINISHED, TripStatus.of(trip, NOW));
    }

    @Test
    void recentlyReportedTripBeforeItsLastStopIsUnderway() {
        var trip = seen(NOW.minusSeconds(60))
                .setScheduledStartAt(at(NOW.minusSeconds(3600)))
                .setLastStopAt(at(NOW.plusSeconds(600)))
                .build();
        assertEquals(TripStatus.UNDERWAY, TripStatus.of(trip, NOW));
    }

    @Test
    void unreportedTripBeforeItsLastStopIsStale() {
        var trip = seen(NOW.minus(TripStatus.STALE_AFTER).minusSeconds(1))
                .setLastStopAt(at(NOW.plusSeconds(600)))
                .build();
        assertEquals(TripStatus.STALE, TripStatus.of(trip, NOW));
    }

    @Test
    void unreportedTripWithoutStopTimesIsStaleNotFinished() {
        var trip = seen(NOW.minusSeconds(3600)).build();
        assertEquals(TripStatus.STALE, TripStatus.of(trip, NOW));
    }

    @Test
    void recentlyReportedTripWithoutStopTimesIsUnderway() {
        assertEquals(TripStatus.UNDERWAY, TripStatus.of(seen(NOW).build(), NOW));
    }
}
