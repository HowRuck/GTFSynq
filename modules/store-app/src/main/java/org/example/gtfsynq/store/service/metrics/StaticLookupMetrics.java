package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

/** Cost of reading the static stops a flush needs. */
@Singleton
public class StaticLookupMetrics {

    private final Timer duration;
    private final DistributionSummary stops;

    public StaticLookupMetrics(MeterRegistry registry) {
        this.duration = Timer.builder("gtfs.sink.static.lookup.duration")
                .description("Time to read the static stops a flush needs")
                .publishPercentileHistogram()
                .register(registry);
        this.stops = DistributionSummary.builder("gtfs.sink.static.lookup.stops")
                .description("Static stop rows loaded per flush")
                .register(registry);
    }

    public void record(int loadedStops, long nanos) {
        duration.record(nanos, TimeUnit.NANOSECONDS);
        stops.record(loadedStops);
    }
}
