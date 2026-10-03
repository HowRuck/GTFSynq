package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.example.gtfsynq.store.service.StaticFeedValidationResult;
import org.springframework.stereotype.Component;

/**
 * Metrics for GTFS static feed validation.
 * <p>
 * Static feeds change on the order of days, so a validation counter on its own cannot tell
 * a healthy quiet pipeline apart from a stalled one: both simply stop producing samples.
 * {@code gtfs.static.validation.last.success.age.seconds} is therefore the primary signal.
 * It is registered per feed the first time that feed is validated and grows until a feed
 * validates successfully, so "no data" becomes an alertable condition rather than an
 * ambiguous one.
 */
@Component
public class StaticFeedValidationMetrics {

    private static final double MILLIS_PER_SECOND = 1000.0;

    /**
     * Result of a single static feed validation attempt.
     */
    public enum Outcome {
        /** The feed was validated and is within the configured error/warning tolerance. */
        PASSED,
        /** The feed was validated but exceeded the configured tolerance. */
        INVALID,
        /** The archive could not be fetched, or the validator could not be run. */
        FAILED
    }

    private final MeterRegistry registry;

    private final Map<String, AtomicLong> lastSuccessEpochMillis = new ConcurrentHashMap<>();

    private final Set<String> ageGaugesRegistered = ConcurrentHashMap.newKeySet();

    public StaticFeedValidationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records the verdict of a completed validation.
     *
     * @param result the validation result
     */
    public void recordResult(StaticFeedValidationResult result) {
        var outcome = result.valid() ? Outcome.PASSED : Outcome.INVALID;

        Counter.builder("gtfs.static.validation")
                .description("Static feed validations by outcome")
                .tags("feed", result.feedId(), "outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();

        // Notices are recorded per severity rather than as one total, because a feed with
        // 10k informational notices is healthy while a feed with 3 errors is not.
        recordNotices(result.feedId(), "errors", result.errorCount());
        recordNotices(result.feedId(), "warnings", result.warningCount());
        recordNotices(result.feedId(), "info", result.infoCount());

        Timer.builder("gtfs.static.validation.duration")
                .description("Time to validate a static feed archive")
                .tag("feed", result.feedId())
                .publishPercentileHistogram()
                .register(registry)
                .record(result.durationNanos(), TimeUnit.NANOSECONDS);

        if (result.valid()) {
            lastSuccess(result.feedId()).set(System.currentTimeMillis());
        }

        registerAgeGauge(result.feedId());
    }

    /**
     * Records an attempt that never produced a verdict, such as an unreachable object
     * store. Timing is recorded by {@link #recordDuration(String, long)} because there is
     * no result to read it from.
     *
     * @param feedId feed source identifier
     */
    public void recordFailure(String feedId) {
        Counter.builder("gtfs.static.validation")
                .description("Static feed validations by outcome")
                .tags("feed", feedId, "outcome", Outcome.FAILED.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();

        registerAgeGauge(feedId);
    }

    /**
     * Records how long an attempt took, whatever its outcome.
     *
     * @param feedId feed source identifier
     * @param durationNanos elapsed time
     */
    public void recordDuration(String feedId, long durationNanos) {
        Timer.builder("gtfs.static.validation.attempt.duration")
                .description("Time spent on a static feed validation attempt")
                .tag("feed", feedId)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private void recordNotices(String feedId, String severity, long count) {
        Counter.builder("gtfs.static.validation.notices")
                .description("Validation notices raised per static feed and severity")
                .tags("feed", feedId, "severity", severity)
                .register(registry)
                .increment(count);
    }

    private void registerAgeGauge(String feedId) {
        if (!ageGaugesRegistered.add(feedId)) {
            return;
        }

        Gauge.builder(
                        "gtfs.static.validation.last.success.age.seconds",
                        lastSuccess(feedId),
                        value -> ageSeconds(value.get()))
                .description("Seconds since this feed last passed static feed validation")
                .tag("feed", feedId)
                .register(registry);
    }

    private AtomicLong lastSuccess(String feedId) {
        // Seeded at the first attempt rather than at success, so a feed that never
        // validates successfully reports a growing age instead of an absent series.
        return lastSuccessEpochMillis.computeIfAbsent(feedId, id -> new AtomicLong(System.currentTimeMillis()));
    }

    private static double ageSeconds(long lastSuccessEpochMillis) {
        return (System.currentTimeMillis() - lastSuccessEpochMillis) / MILLIS_PER_SECOND;
    }
}
