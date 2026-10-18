package org.example.gtfsynq.store.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Metrics for the GTFS static persistence stage.
 *
 * <p>A static load is the one operation in this app that runs for minutes and rewrites whole tables,
 * so the signals are the ones a dashboard needs while it happens: how long it took, how many rows
 * it wrote, and how it ended. The outcome counter keeps "replaced the revision" separate from
 * "the revision was already loaded" - a redelivered event is the normal case after a restart, not
 * a fault, but the two must not read as one number.
 */
@Singleton
public class StaticPersistenceMetrics {

    private static final String REVISIONS = "gtfs.static.persistence.revisions";

    private static final String ROWS = "gtfs.static.persistence.rows";

    private static final String ROWS_BY_TABLE = "gtfs.static.persistence.rows.by_table";

    /** What happened to one validated archive. */
    public enum Result {
        LOADED("loaded"),
        UNCHANGED("unchanged"),
        FAILED("failed"),
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

    private final Map<String, Counter> rowsByTable = new ConcurrentHashMap<>();

    private final Timer loadTimer;

    private final DistributionSummary rowsSummary;

    public StaticPersistenceMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.loadTimer = Timer.builder("gtfs.static.persistence.duration")
                .description("Time to stream a validated archive into the database")
                .publishPercentileHistogram()
                .register(registry);

        this.rowsSummary = DistributionSummary.builder(ROWS)
                .description("Rows written per loaded static revision")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * Counts one archive by result.
     *
     * <p>Counters are created on first use rather than pre-registered, for the same reason as in
     * {@link StaticValidationMetrics}: the set of series depends on the configured feeds.
     *
     * @param feedId feed the revision belongs to, so a failing source can be singled out
     */
    public void recordResult(String feedId, Result result) {
        outcomes.computeIfAbsent(
                        result.tag() + '\u0000' + feedId,
                        ignored -> Counter.builder(REVISIONS)
                                .description("Static revisions by persistence result")
                                .tags("result", result.tag(), "feed", feedId)
                                .register(registry))
                .increment();
    }

    public void recordLoad(long nanos) {
        loadTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Records the rows written by one load.
     *
     * @param feedId feed the rows belong to
     * @param rows total rows written across all tables
     * @param perTable rows per table, so a feed that suddenly loses its stop times is visible
     */
    public void recordRows(String feedId, long rows, Map<String, Long> perTable) {
        rowsSummary.record(rows);
        perTable.forEach((table, count) -> rowsByTableMetric(table, feedId).increment(count));
    }

    private Counter rowsByTableMetric(String table, String feedId) {
        return rowsByTable.computeIfAbsent(
                table + '\u0000' + feedId,
                ignored -> Counter.builder(ROWS_BY_TABLE)
                        .description("Rows written per static table")
                        .tags("table", table, "feed", feedId)
                        .register(registry));
    }
}
