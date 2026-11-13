package org.example.gtfsynq.ui.component.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.NoSuchElementException;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
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

    @Test
    void showsTheLoadingStateUntilTheTripArrives() {
        UI.setCurrent(new UI());
        try {
            var panel = new TripDetailPanel();
            var trip =
                    TripUpdate.newBuilder().setTripUpdateId(1).setFeedId("feed").build();

            panel.showLoading(trip);

            assertTrue(child(panel, "trip-detail-loading").isVisible(), "the loading indicator fills the pane");
            assertFalse(child(panel, "trip-detail-split").isVisible(), "the map/timeline split waits for the data");
            assertFalse(child(panel, "trip-detail-timeline").isVisible(), "the timeline waits for the stop times");

            panel.showTrip(trip, List.of(), NOW);

            assertFalse(child(panel, "trip-detail-loading").isVisible());
            assertTrue(child(panel, "trip-detail-split").isVisible(), "the map and the timeline share the pane");
            assertTrue(child(panel, "trip-detail-timeline").isVisible());
        } finally {
            UI.setCurrent(null);
        }
    }

    /** The first descendant with the class: the split now nests the timeline below the map. */
    private static Component child(Component root, String className) {
        var pending = new ArrayDeque<Component>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var current = pending.remove();
            if (current.hasClassName(className)) {
                return current;
            }
            current.getChildren().forEach(pending::add);
        }
        throw new NoSuchElementException(className);
    }
}
