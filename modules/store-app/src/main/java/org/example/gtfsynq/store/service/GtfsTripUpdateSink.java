package org.example.gtfsynq.store.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    private final List<TripUpdateDto> buffer = new ArrayList<>();

    private final ReentrantLock flushLock = new ReentrantLock();

    /** Guards against queueing more than one background flush at a time. */
    private final AtomicBoolean backgroundFlushRequested = new AtomicBoolean();

    /** Set while the buffer is above its limit, so the overflow is logged once per episode. */
    private final AtomicBoolean limitExceededReported = new AtomicBoolean();

    private final DatabaseDeduplicationService deduplicationService;
    private final GtfsSinkMetrics metrics;

    private final boolean enabled;

    /**
     * Hard cap on buffered updates. When the buffer reaches this size the
     * overflow triggers an early flush
     */
    private final int maxBufferSize;

    @Inject
    public GtfsTripUpdateSink(
            TripUpdateRepository tripUpdateRepository,
            TripUpdateBatchMapper batchMapper,
            DatabaseDeduplicationService deduplicationService,
            GtfsSinkMetrics metrics,
            @ConfigProperty(name = "gtfsynq.sink.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "gtfsynq.sink.max-buffer-size", defaultValue = "20000") int maxBufferSize) {
        this.tripUpdateRepository = tripUpdateRepository;
        this.batchMapper = batchMapper;
        this.deduplicationService = deduplicationService;
        this.metrics = metrics;
        this.enabled = enabled;
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * Accepts a feed entity and buffers it for later batch persistence.
     *
     * <p>The Kafka record is acknowledged as soon as this method returns; the buffer is in-memory
     * only. Deferred acknowledgement was removed because a stuck acknowledgement killed the
     * consumer channel.
     *
     * @param feedId feed identifier from Kafka key
     * @param entityWithMeta decoded GTFS-RT feed entity with metadata
     */
    public void accept(String feedId, FeedEntityWithMetadata entityWithMeta) {
        var entity = entityWithMeta.entity();

        if (!enabled || entity == null || !entity.hasTripUpdate()) {
            return;
        }

        var rawUpdateDto = TripUpdateDto.fromEntity(entity, feedId, entityWithMeta.feedTs());
        if (rawUpdateDto == null) {
            return;
        }

        var cleanedUpdate = deduplicationService.cleanState(rawUpdateDto);
        if (cleanedUpdate == null) {
            metrics.recordDroppedDuplicate();
            return;
        }

        var bufferedSize = 0;
        var overLimit = false;
        bufferLock.lock();
        try {
            buffer.add(cleanedUpdate);
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

        // Backpressure: drain the buffer when it grows past the limit. The flush runs in the
        // background, never on the Kafka consumer thread: an 18-second flush there delayed the
        // batch acknowledgement past the connector's 60-second threshold and killed the channel.
        // The log line reports the episode, not each record: while the buffer stays above the limit
        // every record would otherwise write one line.
        if (overLimit) {
            if (limitExceededReported.compareAndSet(false, true)) {
                log.info("Buffer reached {} buffered updates, flushing early", bufferedSize);
            }
            requestBackgroundFlush();
        }
    }

    /**
     * Flushes the current buffer on a schedule.
     */
    @Scheduled(every = "{gtfsynq.sink.flush-interval}")
    public void scheduledFlush() {
        if (!enabled) {
            return;
        }

        flush();
    }

    /**
     * Flushes any buffered updates immediately.
     */
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
     * <p>Each flush writes through its own pooled connection, so a failed batch leaves the rows it
     * already wrote committed (the upserts are idempotent) and the remainder is retried next tick.
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

            try {
                flushBatch(batch);
            } catch (RuntimeException e) {
                handleFailure(batch, e);
            }
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Handles a failed flush.
     *
     * <p>A data error is a property of one update, not of the batch: the database refuses a value
     * the update carries, such as a feed id that is not in the feed enum. Requeueing the whole
     * batch then wedges ingestion, because every later flush retries the same rejected update and
     * no other update is ever written. Those failures are isolated one update at a time, so the
     * rest of the batch is written and only the offending updates are dropped.
     *
     * <p>Every other failure (connection loss, deadlock, disk full) is not attributable to an
     * update, so the batch is requeued and retried as a whole.
     */
    private void handleFailure(List<TripUpdateDto> batch, RuntimeException failure) {
        if (!isRejectedByDatabase(failure)) {
            requeue(batch);
            throw failure;
        }

        var rejected = 0;
        RuntimeException firstFailure = null;
        for (var update : batch) {
            try {
                flushBatch(List.of(update));
            } catch (RuntimeException e) {
                rejected++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        metrics.recordDroppedRejected(rejected);
        if (firstFailure != null) {
            log.error(
                    "Dropped {} of {} buffered updates the database rejected; the rest were written",
                    rejected,
                    batch.size(),
                    firstFailure);
        }
    }

    /** Whether the failure is the database refusing a value, as opposed to an infrastructure fault. */
    private static boolean isRejectedByDatabase(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (var current = sql; current != null; current = current.getNextException()) {
                    if (isDataSqlState(current.getSQLState())) {
                        return true;
                    }
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * SQL states that mean "this value is not acceptable": data exceptions (22xxx), integrity
     * constraint violations (23xxx) and cardinality violations (21xxx).
     */
    private static boolean isDataSqlState(String sqlState) {
        return sqlState != null
                && (sqlState.startsWith("22") || sqlState.startsWith("23") || sqlState.startsWith("21"));
    }

    /** Runs one flush off the consumer thread, coalescing concurrent requests. */
    private void requestBackgroundFlush() {
        if (!backgroundFlushRequested.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                flush();
            } catch (RuntimeException e) {
                log.warn("Background flush failed; the batch will be retried", e);
            } finally {
                backgroundFlushRequested.set(false);
            }
        });
    }

    /**
     * Swaps the buffer contents into a private batch under the buffer lock.
     * The lock is released before any database work happens.
     */
    private List<TripUpdateDto> drain() {
        bufferLock.lock();
        try {
            if (buffer.isEmpty()) {
                return List.of();
            }
            var batch = new ArrayList<>(buffer);
            buffer.clear();
            limitExceededReported.set(false);
            return batch;
        } finally {
            bufferLock.unlock();
        }
    }

    private void requeue(List<TripUpdateDto> batch) {
        bufferLock.lock();
        try {
            buffer.addAll(0, batch);
        } finally {
            bufferLock.unlock();
        }
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
}
