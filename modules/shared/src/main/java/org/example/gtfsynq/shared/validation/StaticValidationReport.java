package org.example.gtfsynq.shared.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded validation outcome for one static archive.
 *
 * <p>Violation details are capped per file ({@link #MAX_VIOLATIONS_PER_FILE}) so a corrupt {@code
 * stop_times.txt} cannot exhaust memory; counts beyond the cap are retained as {@link
 * FileOutcome#droppedViolations}. Files are keyed by their GTFS name and sorted, so a report reads
 * the same way twice and can be logged or diffed directly.
 *
 * <p>{@link FileOutcome#complete} is {@code false} when a check could not run to the end — a
 * truncated id index, or a stop-sequence check that hit its tracking cap. An incomplete file is not
 * evidence of a valid one.
 */
public record StaticValidationReport(boolean valid, Map<String, FileOutcome> files, long rowsChecked, long violations) {

    /** Maximum retained violation details per file; further violations are counted only. */
    public static final int MAX_VIOLATIONS_PER_FILE = 100;

    public record Violation(String file, long line, String field, String message) {}

    public record FileOutcome(
            boolean present,
            long rows,
            long violations,
            long droppedViolations,
            List<Violation> samples,
            boolean complete) {}

    static final class FileAccumulator {
        boolean present;
        long rows;
        long violations;
        boolean complete = true;
        final List<Violation> samples = new ArrayList<>();

        void add(GtfsStaticFile file, long line, String message) {
            add(file, line, file.fileName(), message);
        }

        void add(GtfsStaticFile file, long line, String field, String message) {
            violations++;
            if (samples.size() < MAX_VIOLATIONS_PER_FILE) {
                samples.add(new Violation(file.fileName(), line, field, message));
            }
        }

        FileOutcome outcome() {
            return new FileOutcome(
                    present, rows, violations, violations - samples.size(), List.copyOf(samples), complete);
        }
    }

    static StaticValidationReport of(Map<String, FileAccumulator> accumulators, long rowsChecked) {
        var files = new TreeMap<String, FileOutcome>();
        var violations = 0L;
        for (var entry : accumulators.entrySet()) {
            var outcome = entry.getValue().outcome();
            files.put(entry.getKey(), outcome);
            violations += outcome.violations();
        }
        // unmodifiableSortedMap rather than unmodifiableMap keeps the sorted contract visible to
        // callers instead of leaving a plain Map that merely happens to be ordered.
        return new StaticValidationReport(
                violations == 0, Collections.unmodifiableSortedMap(files), rowsChecked, violations);
    }
}
