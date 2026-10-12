package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.example.gtfsynq.shared.validation.StaticValidationReport;

/**
 * Metrics for the GTFS static validation stage.
 *
 * <p>Validation is driven by ingestion events rather than a schedule, so the outcome counter is the
 * primary signal: it separates a feed that failed its checksum from one that was fetched and found
 * invalid, which a plain "no data" reading cannot. Per-file violation counts are recorded once per
 * feed rather than per violation, so a corrupt archive costs a handful of counter updates instead
 * of thousands.
 */
@Singleton
public class StaticValidationMetrics {

    private static final String FEEDS = "gtfs.static.validation.feeds";

    private static final String VIOLATIONS = "gtfs.static.validation.violations";

    /** What happened to one ingested archive. */
    public enum Result {
        VALID("valid"),
        INVALID("invalid"),
        CHECKSUM_MISMATCH("checksum_mismatch"),
        FETCH_FAILED("fetch_failed"),
        MALFORMED_EVENT("malformed_event"),
        DISABLED("disabled");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;

    private final Map<String, Counter> outcomes = new ConcurrentHashMap<>();

    private final Map<String, Counter> violationsByFile = new ConcurrentHashMap<>();

    private final Timer fetchTimer;

    private final Timer validationTimer;

    private final DistributionSummary rowsSummary;

    private final DistributionSummary violationsSummary;

    public StaticValidationMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.fetchTimer = Timer.builder("gtfs.static.validation.duration")
                .description("Time to stream an archive out of object storage")
                .tag("stage", "fetch")
                .publishPercentileHistogram()
                .register(registry);

        this.validationTimer = Timer.builder("gtfs.static.validation.duration")
                .description("Time to validate an archive")
                .tag("stage", "validate")
                .publishPercentileHistogram()
                .register(registry);

        this.rowsSummary = DistributionSummary.builder("gtfs.static.validation.rows")
                .description("Rows checked per validated archive")
                .register(registry);

        this.violationsSummary = DistributionSummary.builder("gtfs.static.validation.violations.per.feed")
                .description("Violations found per validated archive")
                .register(registry);
    }

    /**
     * Counts one archive by result.
     *
     * <p>Counters are created on first use rather than pre-registered: with a feed tag the set of
     * series depends on the configured sources, so it cannot be registered up front.
     *
     * @param feedId feed the archive belongs to, so a failing source can be singled out
     */
    public void recordResult(String feedId, Result result) {
        outcomes.computeIfAbsent(
                        result.tag() + '\u0000' + feedId,
                        ignored -> Counter.builder(FEEDS)
                                .description("Ingested static archives by validation result")
                                .tags("result", result.tag(), "feed", feedId)
                                .register(registry))
                .increment();
    }

    public void recordFetch(long nanos) {
        fetchTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordValidation(String feedId, long nanos, StaticValidationReport report) {
        validationTimer.record(nanos, TimeUnit.NANOSECONDS);
        rowsSummary.record(report.rowsChecked());
        violationsSummary.record(report.violations());
        report.files().forEach((file, outcome) -> {
            if (outcome.violations() > 0) {
                violationsByFile
                        .computeIfAbsent(
                                file + '\u0000' + feedId,
                                ignored -> Counter.builder(VIOLATIONS)
                                        .description("Violations found in a static file")
                                        .tags("file", file, "feed", feedId)
                                        .register(registry))
                        .increment(outcome.violations());
            }
        });
    }
}
