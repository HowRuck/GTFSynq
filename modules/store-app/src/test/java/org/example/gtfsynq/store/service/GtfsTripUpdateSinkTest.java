package org.example.gtfsynq.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.transit.realtime.GtfsRealtime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.example.gtfsynq.shared.model.FeedEntityWithMetadata;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateBatchMapper;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRows;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;
import org.junit.jupiter.api.Test;

/**
 * Covers what a flush does when the database refuses part of a batch: one invalid update must not
 * stop every other update from being written, and a transient failure must still be retried.
 */
class GtfsTripUpdateSinkTest {

    private final FakeRepository repository = new FakeRepository();

    private final GtfsTripUpdateSink sink = new GtfsTripUpdateSink(
            repository,
            new TripUpdateBatchMapper(),
            new PassthroughDeduplication(),
            new GtfsSinkMetrics(new SimpleMeterRegistry()),
            true,
            20_000);

    @Test
    void anUpdateTheDatabaseRejectsIsDroppedWhileTheRestOfTheBatchIsWritten() {
        repository.rejectedFeeds = Set.of("bad-feed");

        sink.accept("bad-feed", entity(1));
        sink.accept("good-feed", entity(2));
        sink.flushNow();

        assertEquals(
                Set.of("trip-2"),
                repository.written,
                "the valid update must be written even though the update before it was rejected");
    }

    @Test
    void aTransientFailureKeepsTheBatchForTheNextFlush() {
        repository.transientFailure = true;

        sink.accept("good-feed", entity(3));
        assertThrows(RuntimeException.class, sink::flushNow, "a connection failure is not the update's fault");

        repository.transientFailure = false;
        sink.flushNow();
        assertEquals(Set.of("trip-3"), repository.written, "the requeued batch is written once the database is back");
    }

    private static FeedEntityWithMetadata entity(long id) {
        var tripUpdate = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setTripId("trip-" + id)
                        .setRouteId("route")
                        .setStartDate("20261026")
                        .setStartTime("12:00:00"))
                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                        .setStopSequence(1)
                        .setStopId("stop-1"))
                .build();
        var entity = GtfsRealtime.FeedEntity.newBuilder()
                .setId("entity-" + id)
                .setTripUpdate(tripUpdate)
                .build();
        return new FeedEntityWithMetadata(entity, 0, Instant.ofEpochSecond(1_700_000_000L + id));
    }

    private static final class FakeRepository extends TripUpdateRepository {

        private Set<String> rejectedFeeds = Set.of();

        private boolean transientFailure;

        private final Set<String> written = new LinkedHashSet<>();

        private FakeRepository() {
            super(null);
        }

        @Override
        public FlushTimings write(TripUpdateRows rows) {
            if (transientFailure) {
                throw new RuntimeException(new SQLException("connection reset", "08006"));
            }
            var containsRejectedFeed =
                    rows.descriptors().stream().anyMatch(descriptor -> rejectedFeeds.contains(descriptor.feedId()));
            if (containsRejectedFeed) {
                throw new RuntimeException(new SQLException("invalid input value for enum feed_id_enum", "22P02"));
            }
            rows.descriptors().forEach(descriptor -> written.add(descriptor.tripId()));
            return new FlushTimings(0, 0, 0, 0);
        }
    }

    private static final class PassthroughDeduplication extends DatabaseDeduplicationService {

        private PassthroughDeduplication() {
            super(null);
        }

        @Override
        public TripUpdateDto cleanState(TripUpdateDto entity) {
            return entity;
        }
    }
}
