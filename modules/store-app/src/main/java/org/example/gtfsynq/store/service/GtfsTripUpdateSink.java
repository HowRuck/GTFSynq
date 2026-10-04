package org.example.gtfsynq.store.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.model.FeedEntityWithMetadata;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;

/**
 * Buffers GTFS TripUpdate writes and flushes them to the database in batches.
 *
 * <p>This sink is intended for high-throughput ingestion where individual message writes would be
 * too expensive. Incoming updates are coalesced by entity id so that the latest update wins before
 * being written to the database.
 *
 * <p>The sink keeps only the most recent update per entity id in memory. On flush, it persists the
 * parent trip-update row and all normalized child rows in one transactional operation per entity.
 */
@Singleton
@Slf4j
public class GtfsTripUpdateSink {

    private final TripUpdateRepository tripUpdateRepository;

    private final ReentrantLock bufferLock = new ReentrantLock();
    private final List<TripUpdateDto> buffer = new LinkedList<>();

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
            DatabaseDeduplicationService deduplicationService,
            GtfsSinkMetrics metrics,
            @ConfigProperty(name = "gtfsynq.sink.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "gtfsynq.sink.max-buffer-size", defaultValue = "20000") int maxBufferSize) {
        this.tripUpdateRepository = tripUpdateRepository;
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

        bufferLock.lock();
        try {
            var cleanedUpdate = deduplicationService.cleanState(rawUpdateDto);
            if (cleanedUpdate == null) {
                metrics.recordDroppedDuplicate();
                return;
            }
            buffer.add(cleanedUpdate);

            log.debug(
                    "Buffered TripUpdate entity={} feed={} bufferSize={}",
                    cleanedUpdate.tripDescriptor() != null
                            ? cleanedUpdate.tripDescriptor().entityId()
                            : null,
                    feedId,
                    buffer.size());

            // Backpressure: flush early on the producer thread instead of
            // letting the buffer grow unbounded when intake outpaces the
            // scheduled flush. Runs without the scheduled-flush transaction,
            // so it commits per batch — slower, but correct and self-draining.
            if (buffer.size() >= maxBufferSize) {
                log.info("Buffer reached {} buffered updates, flushing early", buffer.size());
                flushBufferLocked();
            }
        } finally {
            bufferLock.unlock();
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

        bufferLock.lock();
        try {
            flushBufferLocked();
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * Flushes any buffered updates immediately.
     */
    @Transactional
    public void flushNow() {
        if (!enabled) {
            return;
        }

        bufferLock.lock();
        try {
            log.info("Manual flush requested for {} buffered TripUpdate records", buffer.size());
            flushBufferLocked();
        } finally {
            bufferLock.unlock();
        }
    }

    private void flushBufferLocked() {
        if (buffer.isEmpty()) {
            return;
        }

        var methodStart = System.nanoTime();

        var flushSize = buffer.size();

        var tripDescriptors = new ArrayList<TripDescriptorDto>(flushSize);
        var stopTimeUpdates = new ArrayList<TripStopTimeUpdateDto>(flushSize);
        for (var dto : buffer) {
            if (dto.tripDescriptor() != null) {
                tripDescriptors.add(dto.tripDescriptor());
            }
            var rows = dto.stopTimeUpdates();
            if (rows != null) {
                for (var row : rows) {
                    if (row.stopSequence() != null) {
                        stopTimeUpdates.add(row);
                    }
                }
            }
        }
        tripDescriptors.trimToSize();
        stopTimeUpdates.trimToSize();

        metrics.recordEntities(tripDescriptors.size(), stopTimeUpdates.size());

        var start = System.nanoTime();
        tripUpdateRepository.upsertTripDescriptors(tripDescriptors);
        var descriptorsNanos = System.nanoTime() - start;
        metrics.recordDescriptors(descriptorsNanos);

        start = System.nanoTime();
        tripUpdateRepository.appendTripUpdates(stopTimeUpdates);
        var stopTimesNanos = System.nanoTime() - start;
        metrics.recordStopTimes(stopTimesNanos);

        start = System.nanoTime();
        tripUpdateRepository.upsertHotTrips(tripDescriptors, stopTimeUpdates);
        var hotTripsNanos = System.nanoTime() - start;
        metrics.recordHotTrips(hotTripsNanos);

        buffer.clear();

        var totalNanos = System.nanoTime() - methodStart;
        metrics.recordTotal(totalNanos);

        log.info(
                "Flushed {} updates ({} descriptor upserts, {} stop-time rows) in {}ms"
                        + " (descriptors={}ms, stop-times={}ms, hot={}ms)",
                flushSize,
                tripDescriptors.size(),
                stopTimeUpdates.size(),
                totalNanos / 1_000_000,
                descriptorsNanos / 1_000_000,
                stopTimesNanos / 1_000_000,
                hotTripsNanos / 1_000_000);
    }
}
