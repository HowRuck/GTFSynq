package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Singleton
public class GtfsSinkMetrics {

    private static final String GTFS_SINK_DB_WRITE = "gtfs.sink.db.write";

    private final Timer mapTimer;
    private final Timer descriptorTimer;
    private final Timer stopTimeTimer;
    private final Timer hotTripsTimer;
    private final Timer hotStopTimesTimer;
    private final Timer totalFlushTimer;

    private final DistributionSummary descriptorCountSummary;
    private final DistributionSummary stopTimeCountSummary;

    private final Counter droppedDuplicatesCounter;

    private final Counter deterministicFailureCounter;
    private final Counter transientFailureCounter;
    private final Counter deadLetteredBatchCounter;
    private final Counter deadLetteredRowCounter;
    private final Counter deadLetterEvictionRowCounter;
    private final Counter bufferOverflowRowCounter;

    private final AtomicInteger quarantineBatchGauge = new AtomicInteger();
    private final AtomicInteger quarantineRowGauge = new AtomicInteger();
    private final AtomicInteger consecutiveFailureGauge = new AtomicInteger();

    public GtfsSinkMetrics(MeterRegistry registry) {
        this.mapTimer = Timer.builder("gtfs.sink.map.duration")
                .description("Time to shape a drained batch into per-table rows")
                .publishPercentileHistogram()
                .register(registry);

        this.descriptorTimer = Timer.builder(GTFS_SINK_DB_WRITE)
                .tag("op", "upsert_descriptors")
                .publishPercentileHistogram()
                .register(registry);

        this.stopTimeTimer = Timer.builder(GTFS_SINK_DB_WRITE)
                .tag("op", "append_stop_times")
                .publishPercentileHistogram()
                .register(registry);

        this.hotTripsTimer = Timer.builder(GTFS_SINK_DB_WRITE)
                .tag("op", "upsert_hot_trips")
                .publishPercentileHistogram()
                .register(registry);

        this.hotStopTimesTimer = Timer.builder(GTFS_SINK_DB_WRITE)
                .tag("op", "upsert_hot_stop_times")
                .publishPercentileHistogram()
                .register(registry);

        this.totalFlushTimer = Timer.builder("gtfs.sink.flush.duration")
                .description("Total time for mapping and all DB operations")
                .publishPercentileHistogram()
                .register(registry);

        this.descriptorCountSummary = DistributionSummary.builder("gtfs.sink.entities.count")
                .description("Number of descriptors processed in a batch")
                .tag("type", "descriptors")
                .register(registry);

        this.stopTimeCountSummary = DistributionSummary.builder("gtfs.sink.entities.count")
                .description("Number of stop time updates processed in a batch")
                .tag("type", "stop_times")
                .register(registry);

        this.droppedDuplicatesCounter = Counter.builder("gtfs.sink.dropped")
                .description("Fully-duplicate trip updates dropped before buffering")
                .tag("reason", "duplicate")
                .register(registry);

        this.deterministicFailureCounter = Counter.builder("gtfs.sink.flush.failures")
                .description("Failed flushes by whether a retry can succeed")
                .tag("kind", "deterministic")
                .register(registry);

        this.transientFailureCounter = Counter.builder("gtfs.sink.flush.failures")
                .description("Failed flushes by whether a retry can succeed")
                .tag("kind", "transient")
                .register(registry);

        this.deadLetteredBatchCounter = Counter.builder("gtfs.sink.dead_lettered.batches")
                .description("Batches quarantined after repeated deterministic failures")
                .register(registry);

        this.deadLetteredRowCounter = Counter.builder("gtfs.sink.dead_lettered.rows")
                .description("Trip updates moved into the quarantine")
                .register(registry);

        this.deadLetterEvictionRowCounter = Counter.builder("gtfs.sink.dead_letter.evicted.rows")
                .description("Quarantined trip updates evicted when the quarantine was full")
                .register(registry);

        this.bufferOverflowRowCounter = Counter.builder("gtfs.sink.buffer.overflow.rows")
                .description("Buffered trip updates dropped, oldest first, when the buffer ceiling was exceeded")
                .register(registry);

        Gauge.builder("gtfs.sink.quarantine.batches", quarantineBatchGauge, AtomicInteger::get)
                .description("Batches currently held in the quarantine")
                .register(registry);

        Gauge.builder("gtfs.sink.quarantine.rows", quarantineRowGauge, AtomicInteger::get)
                .description("Trip updates currently held in the quarantine")
                .register(registry);

        Gauge.builder("gtfs.sink.consecutive.failures", consecutiveFailureGauge, AtomicInteger::get)
                .description("Consecutive failed flushes since the last success")
                .register(registry);
    }

    public void recordMap(long nanos) {
        mapTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordDescriptors(long nanos) {
        descriptorTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordStopTimes(long nanos) {
        stopTimeTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordHotTrips(long nanos) {
        hotTripsTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordHotStopTimes(long nanos) {
        hotStopTimesTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordTotal(long nanos) {
        totalFlushTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordEntities(int descriptorCount, int stopTimeCount) {
        descriptorCountSummary.record(descriptorCount);
        stopTimeCountSummary.record(stopTimeCount);
    }

    public void recordDroppedDuplicate() {
        droppedDuplicatesCounter.increment();
    }

    /** Records one failed flush, tagged by whether a retry could succeed. */
    public void recordFlushFailure(boolean transientFailure) {
        (transientFailure ? transientFailureCounter : deterministicFailureCounter).increment();
    }

    /** Records a batch moved into the quarantine instead of being retried again. */
    public void recordDeadLettered(int batches, int rows) {
        deadLetteredBatchCounter.increment(batches);
        deadLetteredRowCounter.increment(rows);
    }

    /** Records quarantined trip updates dropped to keep the quarantine bounded. */
    public void recordDeadLetterEviction(int rows) {
        deadLetterEvictionRowCounter.increment(rows);
    }

    /** Records buffered trip updates dropped, oldest first, to keep the buffer bounded. */
    public void recordBufferOverflow(int rows) {
        bufferOverflowRowCounter.increment(rows);
    }

    public void updateConsecutiveFailures(int failures) {
        consecutiveFailureGauge.set(failures);
    }

    public void updateQuarantine(int batches, int rows) {
        quarantineBatchGauge.set(batches);
        quarantineRowGauge.set(rows);
    }
}
