package org.example.gtfsynq.store.service;

/**
 * Outcome of validating one GTFS static feed archive.
 *
 * @param feedId feed source identifier
 * @param sha256 hex encoded digest of the validated archive
 * @param noticeCount total number of validation notices raised
 * @param errorCount notices that violate the GTFS specification
 * @param warningCount notices that flag best-practice or "should" violations
 * @param infoCount notices that do not affect feed quality, e.g. unknown files
 * @param systemErrorCount errors raised by the validator itself rather than by the feed.
 *        A non-zero count means the report is incomplete, independent of the notice counts.
 * @param durationNanos wall clock time the validation took
 * @param valid whether the feed is within the configured error and warning tolerance
 */
public record StaticFeedValidationResult(
        String feedId,
        String sha256,
        long noticeCount,
        long errorCount,
        long warningCount,
        long infoCount,
        int systemErrorCount,
        long durationNanos,
        boolean valid) {

    /**
     * @return a single-line summary suitable for structured logging
     */
    public String summary() {
        return "%s (sha256=%s): %d notices, %d errors, %d warnings, %d info, %d system errors, %dms"
                .formatted(
                        valid ? "valid" : "invalid",
                        sha256,
                        noticeCount,
                        errorCount,
                        warningCount,
                        infoCount,
                        systemErrorCount,
                        durationNanos / 1_000_000);
    }
}
