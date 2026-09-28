package org.example.gtfsynq.ingest.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Metrics for the static feed ingestion pipeline.
 * <p>
 * Static feeds are polled on a schedule measured in days, so counters on their own cannot
 * tell a healthy quiet pipeline apart from a stalled one: both simply produce no samples.
 * {@code gtfs.static.last.success.age.seconds} is therefore the primary signal. It is
 * registered per feed the first time that feed is attempted and grows until the feed
 * ingests successfully, so "no data" becomes an alertable condition rather than an
 * ambiguous one.
 */
@Component
public class StaticFeedMetrics {

    private static final double MILLIS_PER_SECOND = 1000.0;

    /**
     * Result of a single static feed ingestion attempt.
     */
    public enum Outcome {
        /** A new revision was stored and an ingestion event published. */
        SUCCESS,
        /** The archive was already stored, so nothing was uploaded or published. */
        UNCHANGED,
        /** The feed could not be downloaded, stored or published. */
        FAILED,
        /**
         * The attempt outlived {@code gtfsynq.static-feed-timeout-seconds}. The underlying
         * download is not interrupted, so its own outcome may be recorded much later, or
         * never if it hangs.
         */
        TIMEOUT
    }

    private final MeterRegistry registry;

    private final Counter cycleSkippedCounter;

    private final AtomicLong feedsConfigured = new AtomicLong();

    private final Map<String, AtomicLong> lastSuccessEpochMillis = new ConcurrentHashMap<>();

    private final Set<String> ageGaugesRegistered = ConcurrentHashMap.newKeySet();

    public StaticFeedMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.cycleSkippedCounter = Counter.builder("gtfs.static.cycle.skipped")
                .description("Scheduled static ingestion cycles skipped because the previous one was still running")
                .register(registry);

        // Zero when the static config silently loses every source, which is otherwise
        // indistinguishable from a healthy pipeline that simply has nothing to do.
        Gauge.builder("gtfs.static.feeds.configured", feedsConfigured, AtomicLong::doubleValue)
                .description("Static feed sources with a usable static feed URL configured")
                .register(registry);
    }

    /**
     * Records how many sources the current cycle considered, so that a source dropped from
     * configuration is visible instead of merely absent.
     *
     * @param count number of sources with a usable static feed URL
     */
    public void recordFeedsConfigured(int count) {
        feedsConfigured.set(count);
    }

    /**
     * Records the outcome of one ingestion attempt and makes sure the feed's success-age
     * gauge exists, whether or not the attempt succeeded.
     *
     * @param feedId feed source identifier
     * @param outcome result of the attempt
     */
    public void recordOutcome(String feedId, Outcome outcome) {
        Counter.builder("gtfs.static.ingest")
                .description("Static feed ingestion attempts by outcome")
                .tags("feed", feedId, "outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();

        if (outcome == Outcome.SUCCESS) {
            lastSuccess(feedId).set(System.currentTimeMillis());
        }

        registerAgeGauge(feedId);
    }

    /**
     * Records a completed archive download and its size.
     *
     * @param feedId feed source identifier
     * @param durationNanos elapsed download time
     * @param sizeBytes archive size in bytes
     */
    public void recordDownload(String feedId, long durationNanos, long sizeBytes) {
        recordDownloadDuration(feedId, durationNanos);

        DistributionSummary.builder("gtfs.static.archive.size")
                .description("Downloaded static feed archive size")
                .tag("feed", feedId)
                .baseUnit("bytes")
                .register(registry)
                .record(sizeBytes);
    }

    /**
     * Records a download that failed, which has no archive to measure. Failures are counted
     * through {@link #recordOutcome(String, Outcome)}; recording a stand-in zero size here
     * would pollute the size distribution with the very value that means "truncated".
     *
     * @param feedId feed source identifier
     * @param durationNanos elapsed download time before the failure
     */
    public void recordDownloadFailure(String feedId, long durationNanos) {
        recordDownloadDuration(feedId, durationNanos);
    }

    /**
     * Records how long a full scheduled cycle took, from tick to all feeds settled.
     *
     * @param durationNanos elapsed cycle time
     */
    public void recordCycleDuration(long durationNanos) {
        Timer.builder("gtfs.static.cycle.duration")
                .description("Wall clock time for one scheduled static ingestion cycle")
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private void recordDownloadDuration(String feedId, long durationNanos) {
        Timer.builder("gtfs.static.download.duration")
                .description("Time to download a static feed archive")
                .tag("feed", feedId)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a scheduled cycle that was skipped because the previous one had not finished.
     */
    public void recordCycleSkipped() {
        cycleSkippedCounter.increment();
    }

    private void registerAgeGauge(String feedId) {
        if (!ageGaugesRegistered.add(feedId)) {
            return;
        }

        Gauge.builder("gtfs.static.last.success.age.seconds", lastSuccess(feedId), value -> ageSeconds(value.get()))
                .description("Seconds since this feed was last ingested successfully")
                .tag("feed", feedId)
                .register(registry);
    }

    private AtomicLong lastSuccess(String feedId) {
        // Seeded at the first attempt rather than at success, so a feed that never
        // succeeds reports a growing age instead of an absent series.
        return lastSuccessEpochMillis.computeIfAbsent(feedId, id -> new AtomicLong(System.currentTimeMillis()));
    }

    private static double ageSeconds(long lastSuccessEpochMillis) {
        return (System.currentTimeMillis() - lastSuccessEpochMillis) / MILLIS_PER_SECOND;
    }
}
