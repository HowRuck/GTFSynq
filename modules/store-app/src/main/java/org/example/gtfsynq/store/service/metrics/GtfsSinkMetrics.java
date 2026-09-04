package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GtfsSinkMetrics {

    private static final String GTFS_SINK_DB_WRITE = "gtfs.sink.db.write";

    private final Timer descriptorTimer;
    private final Timer stopTimeTimer;
    private final Timer hotTripsTimer;
    private final Timer totalFlushTimer;

    private final DistributionSummary descriptorCountSummary;
    private final DistributionSummary stopTimeCountSummary;

    private final Counter droppedDuplicatesCounter;

    public GtfsSinkMetrics(MeterRegistry registry) {
        this.descriptorTimer = Timer.builder(GTFS_SINK_DB_WRITE)
                .tag("op", "upsert_descriptors")
                .register(registry);

        this.stopTimeTimer =
                Timer.builder(GTFS_SINK_DB_WRITE).tag("op", "append_stop_times").register(registry);

        this.hotTripsTimer =
                Timer.builder(GTFS_SINK_DB_WRITE).tag("op", "upsert_hot_trips").register(registry);

        this.totalFlushTimer = Timer.builder("gtfs.sink.flush.duration")
                .description("Total time for mapping and all DB operations")
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
}
