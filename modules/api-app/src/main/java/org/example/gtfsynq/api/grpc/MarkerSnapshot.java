package org.example.gtfsynq.api.grpc;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.api.repository.GtfsQueryRepository;
import org.example.gtfsynq.shared.protocol.query.TripMarkersReply;

/**
 * Keeps the map's marker snapshot warm, outside the request path.
 *
 * <p>The marker query is the console's most expensive read - every positioned trip in one unpaged
 * reply, with per-trip work on the hot table. Serving it synchronously made the map's latency the
 * query's latency, so a slower deployment simply never loaded: the poll timed out and the next poll
 * started over.
 *
 * <p>Here it is computed in the background: a request gets the current snapshot immediately and only
 * asks for a refresh when the snapshot is older than {@code gtfsynq.api.markers.refresh-interval}.
 * A refresh keeps the previous snapshot on failure, and the very first call - before any snapshot
 * exists - computes one on the calling thread so a cold start still answers.
 */
@ApplicationScoped
@Slf4j
public class MarkerSnapshot {

    private final GtfsQueryRepository repository;

    private final long refreshIntervalNanos;

    private final ExecutorService refresher = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "marker-snapshot");
        thread.setDaemon(true);
        return thread;
    });

    /** Whether a background refresh is already queued or running. */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    /** Serialises the query itself; the flag above only avoids queueing redundant work. */
    private final Object refreshLock = new Object();

    private volatile TripMarkersReply snapshot;

    private volatile long refreshedAt;

    @Inject
    public MarkerSnapshot(
            GtfsQueryRepository repository,
            @ConfigProperty(name = "gtfsynq.api.markers.refresh-interval", defaultValue = "30s")
                    Duration refreshInterval) {
        this.repository = repository;
        this.refreshIntervalNanos = refreshInterval.toNanos();
    }

    /** Computes the first snapshot during startup, so the first console load rarely waits for it. */
    void warmUp(@Observes StartupEvent event) {
        refreshInBackground();
    }

    void shutdown(@Observes ShutdownEvent event) {
        refresher.shutdownNow();
    }

    /**
     * The current snapshot.
     *
     * <p>A caller never waits for a refresh that is merely due: it gets the previous snapshot and
     * the refresh runs in the background. Only the very first call, before any snapshot exists,
     * computes one on the calling thread.
     *
     * @return the marker snapshot, immutable and safe to share between threads
     */
    public TripMarkersReply current() {
        var current = snapshot;
        if (current != null) {
            if (isStale()) {
                refreshInBackground();
            }
            return current;
        }
        return refresh();
    }

    private void refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refresher.execute(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("Could not refresh the live map snapshot, keeping the previous one", e);
            } finally {
                refreshing.set(false);
            }
        });
    }

    /**
     * Runs the query once, under the lock, and only when the snapshot is missing or stale.
     *
     * <p>A failure keeps the previous snapshot and only propagates on a cold start. The timestamp is
     * advanced either way, so a failing query is retried once per interval instead of on every
     * request. Package-private so a test can force the synchronous path.
     */
    TripMarkersReply refresh() {
        synchronized (refreshLock) {
            var current = snapshot;
            if (current != null && !isStale()) {
                return current;
            }
            try {
                var reply = GtfsQueryMapper.tripMarkersReply(repository.liveTripMarkers());
                snapshot = reply;
                refreshedAt = System.nanoTime();
                return reply;
            } catch (RuntimeException e) {
                refreshedAt = System.nanoTime();
                if (current != null) {
                    return current;
                }
                throw e;
            }
        }
    }

    private boolean isStale() {
        var at = refreshedAt;
        return at == 0L || System.nanoTime() - at >= refreshIntervalNanos;
    }
}
