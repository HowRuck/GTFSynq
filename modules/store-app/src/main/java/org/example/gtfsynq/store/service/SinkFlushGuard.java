package org.example.gtfsynq.store.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;

/**
 * Decides what a failed flush does next, so one bad batch cannot block ingestion forever.
 *
 * <p>Without this guard a batch that fails for a deterministic reason is requeued at the head of
 * the buffer on every tick, dragging every update queued behind it along and failing forever. The
 * guard therefore distinguishes two outcomes:
 *
 * <ul>
 *   <li>{@link Decision#RETRY} — return the batch to the buffer and surface the failure. Used for
 *       every transient failure, and for the first few attempts of a deterministic one so a
 *       one-off race can still clear.
 *   <li>{@link Decision#QUARANTINE} — stop retrying this batch, record a compact
 *       {@link QuarantineEntry}, and let ingestion resume with the next drain.
 * </ul>
 *
 * <p>Quarantining keeps diagnostics, not payload: the {@link TripUpdateDto} graphs are released to
 * the garbage collector, so the quarantine costs kilobytes instead of the hundreds of megabytes a
 * retained batch would pin. Replay is deliberately not part of the guard; the entry carries the
 * feed ids, trip ids, attempt count and failure reason needed to investigate and restore the data
 * from its source.
 *
 * <p>Transient failures also flip the pipeline unhealthy after a threshold, so a prolonged outage
 * is visible in {@code /q/health} instead of just in repeated log lines.
 *
 * <p>Not thread-safe by itself: the sink calls it while holding its flush lock.
 */
@Slf4j
class SinkFlushGuard {

    /** What the sink should do with the failed batch. */
    enum Decision {
        RETRY,
        QUARANTINE
    }

    /** Longest failure reason recorded; the sanitized SQL echo still needs a bound here. */
    private static final int REASON_LIMIT = 512;

    /** Trip ids shown in a log line; the entry itself keeps them all. */
    private static final int LOGGED_ID_SAMPLE = 10;

    private final int maxAttempts;
    private final int quarantineCapacity;
    private final int unhealthyAfter;
    private final GtfsSinkMetrics metrics;

    private final Deque<QuarantineEntry> quarantine = new ArrayDeque<>();
    private int quarantinedRows;
    private int consecutiveFailures;
    private boolean unhealthy;

    SinkFlushGuard(int maxAttempts, int quarantineCapacity, int unhealthyAfter, GtfsSinkMetrics metrics) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.quarantineCapacity = Math.max(1, quarantineCapacity);
        this.unhealthyAfter = Math.max(1, unhealthyAfter);
        this.metrics = metrics;
    }

    /** Resets the failure streak after a successful write. */
    void onSuccess() {
        consecutiveFailures = 0;
        unhealthy = false;
        metrics.updateConsecutiveFailures(0);
        metrics.updateQuarantine(quarantine.size(), quarantinedRows);
    }

    /**
     * Registers a failed flush and decides whether the batch is retried or set aside.
     *
     * @param batch   the drained batch that failed
     * @param failure the exception that aborted the write
     * @return whether the caller should requeue the batch for another attempt
     */
    Decision onFailure(List<TripUpdateDto> batch, RuntimeException failure) {
        var transientFailure = SinkFailureClassifier.isTransient(failure);
        metrics.recordFlushFailure(transientFailure);
        consecutiveFailures++;
        metrics.updateConsecutiveFailures(consecutiveFailures);

        if (transientFailure) {
            unhealthy = consecutiveFailures >= unhealthyAfter;
            return Decision.RETRY;
        }

        if (consecutiveFailures < maxAttempts) {
            return Decision.RETRY;
        }

        var entry = quarantine(batch, failure);
        consecutiveFailures = 0;
        metrics.updateConsecutiveFailures(0);
        log.error(
                "Quarantined {} trip updates after {} failed attempts (quarantine holds {} batches / {} rows)."
                        + " Feeds: {}. Trip ids (sample): {}. Reason: {}",
                entry.updateCount(),
                entry.attempts(),
                quarantine.size(),
                quarantinedRows,
                String.join(", ", entry.feedIds()),
                sampleIds(entry.tripUpdateIds()),
                entry.reason());
        return Decision.QUARANTINE;
    }

    private QuarantineEntry quarantine(List<TripUpdateDto> batch, RuntimeException failure) {
        var entry = new QuarantineEntry(
                Instant.now(), feedIds(batch), tripIds(batch), batch.size(), maxAttempts, reasonOf(failure));

        quarantine.addLast(entry);
        quarantinedRows += entry.updateCount();
        metrics.recordDeadLettered(1, entry.updateCount());

        while (quarantine.size() > quarantineCapacity) {
            var evicted = quarantine.removeFirst();
            quarantinedRows -= evicted.updateCount();
            metrics.recordDeadLetterEviction(evicted.updateCount());
            log.warn(
                    "Quarantine full; evicted {} trip updates quarantined at {}. Trip ids (sample): {}",
                    evicted.updateCount(),
                    evicted.quarantinedAt(),
                    sampleIds(evicted.tripUpdateIds()));
        }
        metrics.updateQuarantine(quarantine.size(), quarantinedRows);
        return entry;
    }

    /** A prolonged transient outage is a pipeline problem, not a batch problem. */
    boolean isUnhealthy() {
        return unhealthy;
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    int quarantinedBatches() {
        return quarantine.size();
    }

    int quarantinedRows() {
        return quarantinedRows;
    }

    /**
     * A compact record of a quarantined batch; the payload itself is not retained.
     *
     * <p>All trip ids are kept as primitives: a 10k-update batch costs ~80 KB here instead of the
     * several hundred megabytes its {@code TripUpdateDto} graphs would pin.
     */
    record QuarantineEntry(
            Instant quarantinedAt,
            List<String> feedIds,
            long[] tripUpdateIds,
            int updateCount,
            int attempts,
            String reason) {

        QuarantineEntry {
            feedIds = List.copyOf(feedIds);
            tripUpdateIds = tripUpdateIds.clone();
        }

        /** Defensive copy so callers cannot mutate the stored ids. */
        @Override
        public long[] tripUpdateIds() {
            return tripUpdateIds.clone();
        }
    }

    /** Snapshot of the quarantine, oldest first, for diagnostics and tests. */
    List<QuarantineEntry> quarantineSnapshot() {
        return List.copyOf(new ArrayList<>(quarantine));
    }

    private static List<String> feedIds(List<TripUpdateDto> batch) {
        var feeds = new LinkedHashSet<String>();
        for (var update : batch) {
            var feedId = feedIdOf(update);
            if (feedId != null) {
                feeds.add(feedId);
            }
        }
        return List.copyOf(feeds);
    }

    private static long[] tripIds(List<TripUpdateDto> batch) {
        var ids = new LinkedHashSet<Long>();
        for (var update : batch) {
            var tripId = tripIdOf(update);
            if (tripId != null) {
                ids.add(tripId);
            }
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    private static String reasonOf(RuntimeException failure) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= REASON_LIMIT ? message : message.substring(0, REASON_LIMIT) + "…";
    }

    private static String sampleIds(long[] tripIds) {
        return Arrays.stream(tripIds)
                .limit(LOGGED_ID_SAMPLE)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(", "));
    }

    private static String feedIdOf(TripUpdateDto update) {
        if (update.tripDescriptor() != null) {
            return update.tripDescriptor().feedId();
        }
        var stops = update.stopTimeUpdates();
        if (stops != null && !stops.isEmpty()) {
            return stops.getFirst().feedId();
        }
        return null;
    }

    private static Long tripIdOf(TripUpdateDto update) {
        if (update.tripDescriptor() != null) {
            return update.tripDescriptor().id();
        }
        var stops = update.stopTimeUpdates();
        if (stops != null && !stops.isEmpty()) {
            return stops.getFirst().tripKey();
        }
        return null;
    }
}
