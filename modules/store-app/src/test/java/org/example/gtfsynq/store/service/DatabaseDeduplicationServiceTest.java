package org.example.gtfsynq.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.shared.persistence.OffHeapFileScribe;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers which updates survive deduplication. A feed that publishes a delay is compared by that
 * delay; a feed that publishes only a time (HSL) has to be compared by the time, or every poll
 * after the first looks unchanged and its hot rows age out.
 */
class DatabaseDeduplicationServiceTest {

    private static final String FEED = "helsinki-hsl";

    private static final Instant FEED_TS = Instant.parse("2026-11-01T12:00:00Z");

    private static final Instant PREDICTION = Instant.parse("2026-11-01T12:05:00Z");

    @TempDir
    Path directory;

    private OffHeapHashStore store;

    private DatabaseDeduplicationService service;

    @BeforeEach
    void setUp() {
        store = new OffHeapHashStore(new OffHeapLongTable(
                new OffHeapFileScribe(directory.resolve("state.bin").toString())));
        store.init();
        service = new DatabaseDeduplicationService(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void keepsATimeOnlyPredictionThatMovedToAnotherMinute() {
        assertNotNull(service.cleanState(update(PREDICTION, null)), "the first update is always new");
        assertNull(
                service.cleanState(update(PREDICTION.plusSeconds(20), null)),
                "a prediction in the same minute is not a change");

        var moved = service.cleanState(update(PREDICTION.plusSeconds(120), null));

        assertNotNull(moved, "a prediction in a later minute must be written again");
        assertEquals(1, moved.stopTimeUpdates().size());
    }

    @Test
    void keepsADelayFeedOnItsDelayAndIgnoresItsTimes() {
        assertNotNull(service.cleanState(update(PREDICTION, 120)));

        assertNull(
                service.cleanState(update(PREDICTION.plusSeconds(600), 120)),
                "an unchanged delay is not a change, even when the absolute time moves");
        assertNotNull(
                service.cleanState(update(PREDICTION.plusSeconds(600), 300)),
                "a delay that moved by more than a minute must be written again");
    }

    @Test
    void rewritesAFeedsStopsOnceAfterItsStaticRevisionChanges() {
        var epochs = new StaticRevisionEpochs(feedId -> null);
        service = new DatabaseDeduplicationService(store, epochs);
        assertNotNull(service.cleanState(update(PREDICTION, null)));
        assertNull(service.cleanState(update(PREDICTION, null)), "unchanged before the revision");

        epochs.revisionCommitted(FEED, "digest-2");

        assertNotNull(
                service.cleanState(update(PREDICTION, null)),
                "the stop was stored against the old plan, so it is written again");
        assertNull(service.cleanState(update(PREDICTION, null)), "and only once");
    }

    @Test
    void leavesOtherFeedsAloneWhenOneFeedsRevisionChanges() {
        var epochs = new StaticRevisionEpochs(feedId -> null);
        service = new DatabaseDeduplicationService(store, epochs);
        assertNotNull(service.cleanState(update(PREDICTION, null)));

        epochs.revisionCommitted("some-other-feed", "digest-1");

        assertNull(service.cleanState(update(PREDICTION, null)));
    }

    @Test
    void keepsItsKeysAcrossARestartBecauseTheEpochComesFromTheStoredDigest() {
        service = new DatabaseDeduplicationService(store, new StaticRevisionEpochs(feedId -> "digest-1"));
        assertNotNull(service.cleanState(update(PREDICTION, null)));

        // A new process reads the same digest from the database, so nothing is rewritten.
        service = new DatabaseDeduplicationService(store, new StaticRevisionEpochs(feedId -> "digest-1"));
        assertNull(service.cleanState(update(PREDICTION, null)));

        // A different revision in the database does rewrite the stop.
        service = new DatabaseDeduplicationService(store, new StaticRevisionEpochs(feedId -> "digest-2"));
        assertNotNull(service.cleanState(update(PREDICTION, null)));
    }

    private static TripUpdateDto update(Instant arrival, Integer delay) {
        // HSL's shape: route, start date and start time, but no trip id.
        var descriptor = TripDescriptorDto.fromEntity(
                TripDescriptor.newBuilder()
                        .setRouteId("31M1")
                        .setStartDate("20261101")
                        .setStartTime("12:00:00")
                        .build(),
                FEED,
                "7990512197997432",
                FEED_TS);

        var event = StopTimeEvent.newBuilder().setTime(arrival.getEpochSecond());
        if (delay != null) {
            event.setDelay(delay);
        }
        var stopTimeUpdate = TripStopTimeUpdateDto.fromProto(
                descriptor.id(),
                FEED,
                FEED_TS,
                StopTimeUpdate.newBuilder()
                        .setStopId("2413601")
                        .setArrival(event)
                        .build());

        return new TripUpdateDto(descriptor, List.of(stopTimeUpdate));
    }
}
