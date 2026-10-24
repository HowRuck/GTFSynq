package org.example.gtfsynq.store.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;
import org.junit.jupiter.api.Test;

class SinkFlushGuardTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GtfsSinkMetrics metrics = new GtfsSinkMetrics(registry);

    @Test
    void quarantinesADeterministicFailureOnlyAfterTheAttemptLimit() {
        var guard = guard(3, 3, 6);
        var batch = List.of(update(1));

        assertEquals(SinkFlushGuard.Decision.RETRY, guard.onFailure(batch, deterministic()));
        assertEquals(SinkFlushGuard.Decision.RETRY, guard.onFailure(batch, deterministic()));
        assertEquals(SinkFlushGuard.Decision.QUARANTINE, guard.onFailure(batch, deterministic()));

        assertEquals(1, guard.quarantinedBatches());
        assertEquals(1, guard.quarantinedRows());
        assertEquals(0, guard.consecutiveFailures());
        assertFalse(guard.isUnhealthy(), "a quarantined batch is isolated, not a pipeline outage");
        assertEquals(
                1.0, registry.get("gtfs.sink.dead_lettered.batches").counter().count());

        var entry = guard.quarantineSnapshot().getFirst();
        assertEquals(1, entry.updateCount());
        assertEquals(3, entry.attempts());
        assertEquals(List.of("feed"), entry.feedIds());
        assertArrayEquals(new long[] {1L}, entry.tripUpdateIds());
        assertTrue(entry.reason().contains("foreign key"), "the failure reason is kept for triage");
        assertNotNull(entry.quarantinedAt());
    }

    @Test
    void retriesTransientFailuresForeverWithoutQuarantining() {
        var guard = guard(3, 3, 4);

        for (var attempt = 0; attempt < 10; attempt++) {
            assertEquals(SinkFlushGuard.Decision.RETRY, guard.onFailure(List.of(update(1)), transientFailure()));
        }

        assertEquals(0, guard.quarantinedBatches());
        assertTrue(guard.isUnhealthy(), "a sustained outage must surface through the health check");
    }

    @Test
    void successResetsTheFailureStreak() {
        var guard = guard(3, 3, 4);
        guard.onFailure(List.of(update(1)), deterministic());
        guard.onFailure(List.of(update(1)), deterministic());

        guard.onSuccess();

        assertEquals(0, guard.consecutiveFailures());
        assertEquals(SinkFlushGuard.Decision.RETRY, guard.onFailure(List.of(update(1)), deterministic()));
    }

    @Test
    void successRestoresHealthAfterAProLongedOutage() {
        var guard = guard(3, 3, 2);
        guard.onFailure(List.of(update(1)), transientFailure());
        guard.onFailure(List.of(update(1)), transientFailure());
        assertTrue(guard.isUnhealthy());

        guard.onSuccess();

        assertFalse(guard.isUnhealthy());
        assertEquals(0.0, registry.get("gtfs.sink.consecutive.failures").gauge().value());
    }

    @Test
    void evictsTheOldestQuarantinedBatchWhenFull() {
        var guard = guard(1, 1, 6);

        guard.onFailure(List.of(update(1), update(2)), deterministic());
        guard.onFailure(List.of(update(3)), deterministic());

        assertEquals(1, guard.quarantinedBatches());
        assertEquals(3L, guard.quarantineSnapshot().getFirst().tripUpdateIds()[0]);
        assertEquals(1, guard.quarantinedRows());
        assertEquals(
                2.0,
                registry.get("gtfs.sink.dead_letter.evicted.rows").counter().count());
    }

    private SinkFlushGuard guard(int maxAttempts, int quarantineBatches, int unhealthyAfter) {
        return new SinkFlushGuard(maxAttempts, quarantineBatches, unhealthyAfter, metrics);
    }

    private static RuntimeException deterministic() {
        return new RuntimeException(new SQLException("violates foreign key constraint", "23503"));
    }

    private static RuntimeException transientFailure() {
        return new RuntimeException(new SQLException("connection lost", "08006"));
    }

    private static TripUpdateDto update(long id) {
        return new TripUpdateDto(
                new TripDescriptorDto(
                        id, "entity-" + id, "feed", Instant.EPOCH, "trip", "route", 0, null, null, (short) 0, 0L),
                List.of());
    }
}
