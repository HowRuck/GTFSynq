package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.List;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.junit.jupiter.api.Test;

class TripDetailPanelTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:10:00Z");

    @Test
    void marksTheMostAdvancedStopThatIsNotInTheFutureAsCurrent() {
        var stops =
                List.of(stopAt("2026-10-30T10:05:00Z"), stopAt("2026-10-30T10:08:00Z"), stopAt("2026-10-30T10:12:00Z"));

        assertEquals(1, TripDetailPanel.currentIndex(stops, NOW));
    }

    @Test
    void hasNoCurrentStopBeforeTheTripStarts() {
        var stops = List.of(stopAt("2026-10-30T10:12:00Z"), stopAt("2026-10-30T10:15:00Z"));

        assertEquals(-1, TripDetailPanel.currentIndex(stops, NOW));
    }

    @Test
    void usesTheScheduledTimeShiftedByADelayOnlyPrediction() {
        var scheduled = Instant.parse("2026-10-30T10:00:00Z");
        var stop = StopTimeUpdate.newBuilder()
                .setScheduledArrivalTime(timestamp(scheduled))
                .setArrivalDelay(600)
                .build();

        assertEquals(scheduled.plusSeconds(600), TripDetailPanel.stopInstant(stop));
    }

    @Test
    void prefersTheLivePredictionOverTheSchedule() {
        var live = Instant.parse("2026-10-30T10:02:00Z");
        var stop = StopTimeUpdate.newBuilder()
                .setArrivalTime(timestamp(live))
                .setScheduledArrivalTime(timestamp(Instant.parse("2026-10-30T10:00:00Z")))
                .setArrivalDelay(600)
                .build();

        assertEquals(live, TripDetailPanel.stopInstant(stop));
    }

    @Test
    void leavesStopsWithoutAnyTimeUnclassified() {
        var stop = StopTimeUpdate.newBuilder().setStopId("A").build();

        assertNull(TripDetailPanel.stopInstant(stop));
        assertEquals(-1, TripDetailPanel.currentIndex(List.of(stop), NOW));
    }

    private static StopTimeUpdate stopAt(String instant) {
        return StopTimeUpdate.newBuilder()
                .setArrivalTime(timestamp(Instant.parse(instant)))
                .build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
