package org.example.gtfsynq.store.adapter.outbound.database;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;

/**
 * Bounds the size of JDBC batch-failure messages before they reach the log.
 *
 * <p>pgjdbc echoes the full SQL of a failed batch entry in every exception message. With {@code
 * reWriteBatchedInserts} the batch is merged into a single multi-row INSERT, and the rewritten
 * values are inlined into that message, so one failing entry can reach megabytes. At flush cadence
 * a repeating error then turns into gigabytes of log.
 *
 * <p>The driver detail ({@code ERROR}, {@code Detail}, hint) sits at the message tail, so a head and
 * tail snippet are kept and the rewritten SQL is elided in between. Messages that already fit are
 * reused unchanged, preserving the original exception objects and their links.
 *
 * <p>Call this wherever {@link java.sql.PreparedStatement#executeBatch()} runs: a single missed
 * call site reopens the log-flood path.
 */
@Slf4j
final class SqlLogSanitizer {

    /** Chars kept at each end of an oversized message around the elision marker. */
    static final int SNIPPET = 400;

    private SqlLogSanitizer() {}

    /**
     * Returns an equivalent exception chain whose oversized messages are elided.
     *
     * <p>The chain structure is preserved: causes stay causes and next exceptions stay next exceptions,
     * still pointing at the same links where those were short enough to keep. Rewritten exceptions are
     * recreated with their SQL state and, for batch failures, their update counts.
     *
     * @param e the exception chain to sanitize
     * @return the original instance when nothing needed truncating, otherwise a truncated copy
     */
    static SQLException sanitize(SQLException e) {
        var next = e.getNextException();
        var cause = e.getCause();
        // next and cause can reference the same underlying error; following both would duplicate it
        // in every log line
        var nextIsCause = next != null && next == cause;
        var sanitizedNext = next == null || nextIsCause ? null : sanitize(next);

        Throwable sanitizedCause = cause;
        if (cause instanceof SQLException causeSql) {
            sanitizedCause = sanitize(causeSql);
        }

        var message = truncateSqlEcho(e.getMessage());

        if (message == e.getMessage() && sanitizedCause == cause && sanitizedNext == next) {
            return e;
        }

        var replacement = e instanceof BatchUpdateException batch
                ? new BatchUpdateException(message, e.getSQLState(), batch.getUpdateCounts(), sanitizedCause)
                : new SQLException(message, e.getSQLState(), sanitizedCause);

        if (sanitizedNext != null) {
            replacement.setNextException(sanitizedNext);
        }
        return replacement;
    }

    /**
     * Elides the middle of an oversized message with a marker that also reports how many chars were
     * dropped. The full message is available at {@code TRACE} for active debugging.
     *
     * @param message the full exception message, or {@code null}
     * @return the message, or itself unchanged when it is short enough
     */
    private static String truncateSqlEcho(String message) {
        if (message == null || message.length() <= 2 * SNIPPET + 64) {
            return message;
        }
        log.trace("Full batch failure message before truncation ({} chars): {}", message.length(), message);
        var elided = message.length() - 2 * SNIPPET;
        return message.substring(0, SNIPPET)
                + "\n  ... [" + elided + " chars of rewritten batch SQL elided] ...\n  "
                + message.substring(message.length() - SNIPPET);
    }
}
