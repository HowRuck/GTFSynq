package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;

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

    private final Counter droppedRejectedCounter;

    private final MeterRegistry registry;

    private final ConcurrentMap<DelayKey, Counter> delayCounters = new ConcurrentHashMap<>();

    /** Where a stored delay came from; {@code NONE} rows have no delay to aggregate. */
    private enum DelaySource {
        PUBLISHED,
        DERIVED,
        NONE
    }

    private record DelayKey(String feedId, DelaySource source) {}

    public GtfsSinkMetrics(MeterRegistry registry) {
        this.registry = registry;
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

        this.droppedRejectedCounter = Counter.builder("gtfs.sink.dropped")
                .description("Trip updates dropped because the database rejected them as invalid")
                .tag("reason", "rejected")
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

    /** Records updates dropped because the database rejected them as invalid. */
    public void recordDroppedRejected(long count) {
        if (count > 0) {
            droppedRejectedCounter.increment(count);
        }
    }

    /**
     * Counts the stored stop-time rows by where their delay came from, so a feed that cannot be tied
     * to the static schedule shows up as a growing {@code none} share.
     */
    public void recordDelaySources(Collection<TripStopTimeUpdateDto> stopTimes) {
        var tally = new HashMap<DelayKey, Long>();
        for (var row : stopTimes) {
            tally.merge(new DelayKey(row.feedId(), delaySourceOf(row)), 1L, Long::sum);
        }
        tally.forEach((key, count) -> delayCounters
                .computeIfAbsent(
                        key,
                        k -> Counter.builder("gtfs.sink.delay")
                                .description("Stop-time rows by where their stored delay came from")
                                .tag("feed", k.feedId())
                                .tag("source", k.source().name().toLowerCase(Locale.ROOT))
                                .register(registry))
                .increment(count));
    }

    private static DelaySource delaySourceOf(TripStopTimeUpdateDto row) {
        if (row.arrivalDelay() != null || row.departureDelay() != null) {
            return DelaySource.PUBLISHED;
        }
        return row.delaySeconds() != null ? DelaySource.DERIVED : DelaySource.NONE;
    }
}
