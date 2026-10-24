package org.example.gtfsynq.store.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.example.gtfsynq.shared.model.FeedEntityWithMetadata;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateBatchMapper;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRows;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;
import org.junit.jupiter.api.Test;

class GtfsTripUpdateSinkTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeRepository repository = new FakeRepository();
    private final GtfsTripUpdateSink sink = new GtfsTripUpdateSink(
            repository,
            new TripUpdateBatchMapper(),
            new PassthroughDeduplication(),
            new GtfsSinkMetrics(registry),
            true,
            20_000,
            2,
            3,
            3,
            6);

    @Test
    void quarantinesAPoisonedBatchAndResumesWithNewUpdates() {
        repository.failure = deterministic();

        sink.accept("feed", entity(1));
        assertThrows(RuntimeException.class, sink::flushNow);
        assertThrows(RuntimeException.class, sink::flushNow);
        sink.flushNow();

        assertEquals(1.0, gauge("gtfs.sink.quarantine.batches"));
        assertEquals(1.0, counter("gtfs.sink.dead_lettered.batches"));
        assertEquals(0.0, gauge("gtfs.sink.consecutive.failures"));

        repository.failure = null;
        sink.accept("feed", entity(2));
        sink.flushNow();

        assertEquals(1, repository.writtenUpdates, "the quarantined batch must not be retried");
        assertEquals(1.0, gauge("gtfs.sink.quarantine.batches"), "the quarantined batch stays parked");
    }

    @Test
    void capsTheBufferByDroppingOldestUpdatesDuringATransientOutage() {
        repository.failure = transientFailure();

        sink.accept("feed", entity(1));
        sink.accept("feed", entity(2));
        sink.accept("feed", entity(3));
        assertThrows(RuntimeException.class, sink::flushNow);

        assertEquals(1.0, counter("gtfs.sink.buffer.overflow.rows"));

        repository.failure = null;
        sink.flushNow();

        assertEquals(2, repository.writtenUpdates, "only the newest two updates survive the ceiling");
        assertEquals(0.0, gauge("gtfs.sink.consecutive.failures"), "success resets the streak");
    }

    @Test
    void becomesUnhealthyAfterSustainedTransientFailuresAndRecoversOnSuccess() {
        repository.failure = transientFailure();
        sink.accept("feed", entity(1));

        for (var attempt = 0; attempt < 6; attempt++) {
            assertThrows(RuntimeException.class, sink::flushNow);
        }
        assertTrue(sink.isUnhealthy());

        repository.failure = null;
        sink.flushNow();

        assertFalse(sink.isUnhealthy());
        assertEquals(1, repository.writtenUpdates);
    }

    @Test
    void healthCheckFollowsTheSinkState() {
        repository.failure = transientFailure();
        sink.accept("feed", entity(1));

        for (var attempt = 0; attempt < 6; attempt++) {
            assertThrows(RuntimeException.class, sink::flushNow);
        }

        assertEquals(
                HealthCheckResponse.Status.DOWN,
                new TripUpdateSinkHealthCheck(sink).call().getStatus());
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private static RuntimeException deterministic() {
        return new RuntimeException(new SQLException("violates foreign key constraint", "23503"));
    }

    private static RuntimeException transientFailure() {
        return new RuntimeException(new SQLException("connection lost", "08006"));
    }

    private static FeedEntityWithMetadata entity(long id) {
        var tripUpdate = TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder()
                        .setTripId("trip-" + id)
                        .setRouteId("route")
                        .setStartDate("20261024")
                        .setStartTime("12:00:00"))
                .build();
        return new FeedEntityWithMetadata(
                FeedEntity.newBuilder()
                        .setId("entity-" + id)
                        .setTripUpdate(tripUpdate)
                        .build(),
                0,
                Instant.EPOCH);
    }

    private static final class FakeRepository extends TripUpdateRepository {

        private RuntimeException failure;
        private int writtenUpdates;

        private FakeRepository() {
            super(null);
        }

        @Override
        public FlushTimings write(TripUpdateRows rows) {
            if (failure != null) {
                throw failure;
            }
            writtenUpdates += rows.descriptors().size();
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
