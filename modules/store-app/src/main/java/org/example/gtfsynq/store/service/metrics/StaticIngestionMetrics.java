package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Metrics for one static-feed event across staging, validation, and persistence. */
@Singleton
public class StaticIngestionMetrics {

    private static final String DURATION = "gtfs.static.ingestion.duration";

    private final MeterRegistry registry;

    private final LongTaskTimer inFlight;

    private final Map<String, Timer> durationsByOutcome = new ConcurrentHashMap<>();

    public StaticIngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.inFlight = LongTaskTimer.builder("gtfs.static.ingestion.in_flight")
                .description("Active static-feed ingestions and their elapsed time")
                .register(registry);
    }

    /** Starts tracking both active age and the eventual end-to-end duration. */
    public Sample start() {
        return new Sample(Timer.start(registry), inFlight.start());
    }

    /** Stops the active-age measurement and records the completed duration by outcome. */
    public void complete(Sample sample, String outcome) {
        try {
            sample.duration().stop(durationFor(outcome));
        } finally {
            sample.inFlight().stop();
        }
    }

    private Timer durationFor(String outcome) {
        return durationsByOutcome.computeIfAbsent(
                outcome,
                result -> Timer.builder(DURATION)
                        .description("End-to-end time to process a static-feed event")
                        .tag("outcome", result)
                        .publishPercentileHistogram()
                        .register(registry));
    }

    public record Sample(Timer.Sample duration, LongTaskTimer.Sample inFlight) {}
}
