package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Metrics for one static-feed event across staging, validation, and persistence. */
@Singleton
public class StaticIngestionMetrics {

    private static final String DURATION = "gtfs.static.ingestion.duration";

    private final MeterRegistry registry;

    private final LongTaskTimer inFlight;

    private final Timer queueAge;

    private final DistributionSummary archiveSize;

    private final Map<String, Timer> durationsByOutcome = new ConcurrentHashMap<>();

    public StaticIngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.inFlight = LongTaskTimer.builder("gtfs.static.ingestion.in_flight")
                .description("Active static-feed ingestions and their elapsed time")
                .register(registry);
        this.queueAge = Timer.builder("gtfs.static.ingestion.queue.age")
                .description("Time from static archive publication to the start of processing")
                .publishPercentileHistogram()
                .register(registry);
        this.archiveSize = DistributionSummary.builder("gtfs.static.ingestion.archive.size")
                .description("Compressed static archive size in bytes as reported by the ingestion event")
                .publishPercentileHistogram()
                .register(registry);
    }

    /** Records event age at processing start and the archive size carried in the event. */
    public void recordEventMetadata(long sizeBytes, long ingestedAtEpochSeconds) {
        if (sizeBytes > 0) {
            archiveSize.record(sizeBytes);
        }
        if (ingestedAtEpochSeconds > 0) {
            var queueAgeSeconds = Math.max(0, Instant.now().getEpochSecond() - ingestedAtEpochSeconds);
            queueAge.record(queueAgeSeconds, TimeUnit.SECONDS);
        }
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
