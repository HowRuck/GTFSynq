package org.example.gtfsynq.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Covers the map's snapshot: one query per interval, a failed refresh keeps the previous snapshot,
 * and only a cold start fails.
 */
class MarkerSnapshotTest {

    private final GtfsQueryGrpcServiceTest.CountingRepository repository =
            new GtfsQueryGrpcServiceTest.CountingRepository();

    @Test
    void computesTheSnapshotOncePerInterval() {
        var snapshot = new MarkerSnapshot(repository, Duration.ofSeconds(10));

        var first = snapshot.current();
        var second = snapshot.current();

        assertEquals(1, repository.calls, "a poll within the interval must reuse the snapshot");
        assertEquals(1, first.getItemsCount());
        assertEquals("10", second.getItems(0).getRouteShortName());
    }

    @Test
    void keepsThePreviousSnapshotWhenARefreshFails() {
        var snapshot = new MarkerSnapshot(repository, Duration.ZERO);
        snapshot.refresh();
        repository.failing = true;

        var reply = snapshot.refresh();

        assertEquals(1, reply.getItemsCount(), "a failed refresh must not blank the map");
        assertEquals(2, repository.calls);
    }

    @Test
    void failsAColdStart() {
        var snapshot = new MarkerSnapshot(repository, Duration.ofSeconds(10));
        repository.failing = true;

        assertThrows(IllegalStateException.class, snapshot::refresh);
    }
}
