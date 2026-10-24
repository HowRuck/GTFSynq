package org.example.gtfsynq.store.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.model.FeedEntityWithMetadata;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateBatchMapper;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;

/**
 * Buffers GTFS TripUpdate writes and flushes them to the database in batches.
 *
 * <p>This sink is intended for high-throughput ingestion where individual message writes would be
 * too expensive. Incoming updates are deduplicated upstream, so the buffer only holds changed
 * data. On flush, the drained batch is shaped into per-table rows by {@link TripUpdateBatchMapper}
 * and handed to {@link TripUpdateRepository} for persistence.
 *
 * <p>Locking discipline: {@code bufferLock} guards only the buffer swap and is
 * never held during database I/O, so Kafka consumer threads keep buffering
 * while a flush is in flight. Concurrent flushes (scheduled vs. early) are
 * serialized by {@code flushLock} instead.
 */
@Singleton
@Slf4j
public class GtfsTripUpdateSink {

    private final TripUpdateRepository tripUpdateRepository;
    private final TripUpdateBatchMapper batchMapper;

    private final ReentrantLock bufferLock = new ReentrantLock();
    private final List<BufferedUpdate> buffer = new ArrayList<>();

    private final ReentrantLock flushLock = new ReentrantLock();

    private final DatabaseDeduplicationService deduplicationService;
    private final GtfsSinkMetrics metrics;
    private final SinkFlushGuard guard;
    private final AckScheduler ackScheduler;

    private final boolean enabled;

    /**
     * Hard cap on buffered updates. When the buffer reaches this size the
     * overflow triggers an early flush
     */
    private final int maxBufferSize;

    /**
     * Ceiling for the buffer while flushes are failing; the oldest updates are
     * dropped once it is crossed, so a database outage cannot grow the buffer
     * without bound.
     */
    private final int maxBufferedUpdates;

    @Inject
    public GtfsTripUpdateSink(
            TripUpdateRepository tripUpdateRepository,
            TripUpdateBatchMapper batchMapper,
            DatabaseDeduplicationService deduplicationService,
            GtfsSinkMetrics metrics,
            AckScheduler ackScheduler,
            @ConfigProperty(name = "gtfsynq.sink.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "gtfsynq.sink.max-buffer-size", defaultValue = "20000") int maxBufferSize,
            @ConfigProperty(name = "gtfsynq.sink.max-buffered-updates", defaultValue = "40000") int maxBufferedUpdates,
            @ConfigProperty(name = "gtfsynq.sink.failure.max-attempts", defaultValue = "3") int failureMaxAttempts,
            @ConfigProperty(name = "gtfsynq.sink.failure.quarantine-batches", defaultValue = "3") int quarantineBatches,
            @ConfigProperty(name = "gtfsynq.sink.failure.unhealthy-after", defaultValue = "6") int unhealthyAfter) {
        this.tripUpdateRepository = tripUpdateRepository;
        this.batchMapper = batchMapper;
        this.deduplicationService = deduplicationService;
        this.metrics = metrics;
        this.ackScheduler = ackScheduler;
        this.enabled = enabled;
        this.maxBufferSize = maxBufferSize;
        this.maxBufferedUpdates = maxBufferedUpdates;
        this.guard = new SinkFlushGuard(failureMaxAttempts, quarantineBatches, unhealthyAfter, metrics);
    }

    /**
     * Whether the flush pipeline is considered degraded, i.e. it has failed
     * repeatedly for reasons a retry could fix.
     */
    public boolean isUnhealthy() {
        return guard.isUnhealthy();
    }

    /** Consecutive failed flushes since the last success; exposed for the health check. */
    public int consecutiveFailures() {
        return guard.consecutiveFailures();
    }

    /**
     * Accepts a feed entity and buffers it for later batch persistence.
     *
     * <p>The acknowledgement is deferred: it runs only once the update's batch has been written and
     * committed, so a crash between buffering and persisting replays the Kafka record instead of
     * dropping it. Updates that are deliberately not persisted (sink disabled, not a TripUpdate,
     * duplicate) are acknowledged right away - they have no batch to wait for.
     *
     * @param feedId feed identifier from Kafka key
     * @param entityWithMeta decoded GTFS-RT feed entity with metadata
     * @param onPersisted acknowledgement releasing the Kafka record, run once the update is durable
     */
    public void accept(String feedId, FeedEntityWithMetadata entityWithMeta, Runnable onPersisted) {
        var entity = entityWithMeta.entity();

        if (!enabled || entity == null || !entity.hasTripUpdate()) {
            acknowledgeNow(onPersisted);
            return;
        }

        var rawUpdateDto = TripUpdateDto.fromEntity(entity, feedId, entityWithMeta.feedTs());
        if (rawUpdateDto == null) {
            acknowledgeNow(onPersisted);
            return;
        }

        var cleanedUpdate = deduplicationService.cleanState(rawUpdateDto);
        if (cleanedUpdate == null) {
            metrics.recordDroppedDuplicate();
            acknowledgeNow(onPersisted);
            return;
        }

        var bufferedSize = 0;
        var overLimit = false;
        bufferLock.lock();
        try {
            buffer.add(new BufferedUpdate(cleanedUpdate, onPersisted));
            bufferedSize = buffer.size();
            overLimit = bufferedSize >= maxBufferSize;
        } finally {
            bufferLock.unlock();
        }

        log.debug(
                "Buffered TripUpdate entity={} feed={} bufferSize={}",
                cleanedUpdate.tripDescriptor() != null
                        ? cleanedUpdate.tripDescriptor().entityId()
                        : null,
                feedId,
                bufferedSize);

        // Backpressure: flush early on the producer thread instead of
        // letting the buffer grow unbounded when intake outpaces the
        // scheduled flush. The buffer lock is already released here, so
        // other producers keep buffering while this flush runs.
        if (overLimit) {
            log.info("Buffer reached {} buffered updates, flushing early", bufferedSize);
            flush();
        }
    }

    /**
     * Flushes the current buffer on a schedule.
     */
    @Scheduled(every = "{gtfsynq.sink.flush-interval}")
    @Transactional
    public void scheduledFlush() {
        if (!enabled) {
            return;
        }

        flush();
    }

    /**
     * Flushes any buffered updates immediately.
     */
    @Transactional
    public void flushNow() {
        if (!enabled) {
            return;
        }

        log.info("Manual flush requested for {} buffered TripUpdate records", buffer.size());
        flush();
    }

    /**
     * Drains the buffer and persists the drained batch. A failed batch is
     * re-queued at the head of the buffer so it is retried on the next flush,
     * matching the previous behavior where the buffer was only cleared after
     * a successful write.
     *
     * <p>A deterministic failure is retried a few times and then quarantined:
     * the batch is moved to a bounded side buffer instead of being requeued, so
     * one poisoned batch cannot block the updates queued behind it. The write
     * in progress is abandoned either way; because the caller runs in a single
     * transaction, a failed flush commits as a rollback.
     *
     * <p>{@code flushLock} is taken before the drain so that a drain and its
     * write stay atomic with respect to other flushes. This preserves
     * write ordering: without it, a slow flush could lose the race and write an
     * older drained batch after a newer one, letting stale rows win the
     * upsert on the hot/meta tables. Producers are unaffected because they only
     * contend on {@code bufferLock}, which is released as soon as the drain
     * swaps the buffer.
     */
    private void flush() {
        flushLock.lock();
        try {
            var batch = drain();
            if (batch.isEmpty()) {
                return;
            }

            var updates = batch.stream().map(BufferedUpdate::update).toList();

            try {
                flushBatch(updates);
                guard.onSuccess();
                // Release the records only once the write has committed, so a rollback or a crash
                // between drain and commit leaves them unacknowledged and Kafka replays them.
                ackScheduler.afterCommit(() -> batch.forEach(BufferedUpdate::acknowledge));
            } catch (RuntimeException e) {
                // A retried batch is requeued in front of newer updates to keep
                // write ordering; a quarantined one is dropped from the retry
                // path so healthy traffic is not blocked by it.
                if (guard.onFailure(updates, e) == SinkFlushGuard.Decision.RETRY) {
                    requeue(batch);
                    throw e;
                }
                // Quarantined: the payload is discarded by design, so its records are released
                // rather than left to stall the partition behind an acknowledgement that will
                // never come. Quarantine metrics and logs already record the loss.
                batch.forEach(BufferedUpdate::acknowledge);
            }
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Swaps the buffer contents into a private batch under the buffer lock.
     * The lock is released before any database work happens.
     */
    private List<BufferedUpdate> drain() {
        bufferLock.lock();
        try {
            if (buffer.isEmpty()) {
                return List.of();
            }
            var batch = new ArrayList<>(buffer);
            buffer.clear();
            return batch;
        } finally {
            bufferLock.unlock();
        }
    }

    private void requeue(List<BufferedUpdate> batch) {
        List<BufferedUpdate> dropped = List.of();
        bufferLock.lock();
        try {
            buffer.addAll(0, batch);
            var overflow = buffer.size() - maxBufferedUpdates;
            if (overflow > 0) {
                // The oldest updates are the least useful for current-state
                // consumers, so they are the ones dropped when the buffer is
                // full during a prolonged failure.
                dropped = new ArrayList<>(buffer.subList(0, overflow));
                buffer.subList(0, overflow).clear();
                metrics.recordBufferOverflow(overflow);
                log.warn(
                        "Buffer exceeded {} updates while retrying; dropped {} oldest updates",
                        maxBufferedUpdates,
                        overflow);
            }
        } finally {
            bufferLock.unlock();
        }

        // A dropped update will never be written, so its record is released instead of waiting for
        // an acknowledgement that can never arrive.
        dropped.forEach(BufferedUpdate::acknowledge);
    }

    private void flushBatch(List<TripUpdateDto> batch) {
        var methodStart = System.nanoTime();

        var mapStart = System.nanoTime();
        var rows = batchMapper.map(batch);
        var mapNanos = System.nanoTime() - mapStart;
        metrics.recordMap(mapNanos);

        metrics.recordEntities(
                rows.descriptors().size(), rows.historyStopTimes().size());

        // One shared-connection write for descriptors, history, and hot tables.
        var timings = tripUpdateRepository.write(rows);
        metrics.recordDescriptors(timings.descriptorsNanos());
        metrics.recordStopTimes(timings.stopTimesNanos());
        metrics.recordHotTrips(timings.hotTripRowsNanos());
        metrics.recordHotStopTimes(timings.hotStopTimesNanos());

        var totalNanos = System.nanoTime() - methodStart;
        metrics.recordTotal(totalNanos);

        log.info(
                "Flushed {} updates ({} descriptor upserts, {} stop-time rows) in {}ms (map={}ms,"
                        + " descriptors={}ms, stop-times={}ms, hot-trips={}ms, hot-stops={}ms)",
                batch.size(),
                rows.descriptors().size(),
                rows.historyStopTimes().size(),
                totalNanos / 1_000_000,
                mapNanos / 1_000_000,
                timings.descriptorsNanos() / 1_000_000,
                timings.stopTimesNanos() / 1_000_000,
                timings.hotTripRowsNanos() / 1_000_000,
                timings.hotStopTimesNanos() / 1_000_000);
    }

    /** Releases a record whose update is discarded instead of buffered. */
    private void acknowledgeNow(Runnable acknowledgement) {
        try {
            acknowledgement.run();
        } catch (RuntimeException e) {
            log.warn("Failed to acknowledge a discarded TripUpdate", e);
        }
    }

    /**
     * A buffered update paired with the acknowledgement that releases its Kafka record.
     *
     * <p>Releasing is idempotent: an entry leaves the sink exactly once, whether it was persisted,
     * quarantined, or dropped by the overflow ceiling, so a double release can never reach the
     * messaging layer and fail the consumer.
     */
    private static final class BufferedUpdate {

        private final TripUpdateDto update;
        private final Runnable acknowledgement;
        private final AtomicBoolean released = new AtomicBoolean();

        private BufferedUpdate(TripUpdateDto update, Runnable acknowledgement) {
            this.update = update;
            this.acknowledgement = acknowledgement;
        }

        private TripUpdateDto update() {
            return update;
        }

        private void acknowledge() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            try {
                acknowledgement.run();
            } catch (RuntimeException e) {
                log.warn("Failed to acknowledge a buffered TripUpdate", e);
            }
        }
    }
}
