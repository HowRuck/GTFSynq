package org.example.gtfsynq.store.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
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
                requeue(batch);
                throw e;
            }
        } finally {
            flushLock.unlock();
        }
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

        metrics.recordEntities(rows.descriptors().size(), rows.historyStopTimes().size());

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
