package org.example.gtfsynq.store.service;

import java.sql.SQLException;
import java.util.Set;

/**
 * Separates failures that a retry can fix from failures that only a new batch can.
 *
 * <p>The distinction drives the flush guard: a transient failure (database unreachable, deadlock,
 * resource exhaustion) is retried forever, while a deterministic failure (constraint or data
 * exception) is quarantined once retrying has proven pointless. Unknown causes count as
 * deterministic, because spinning on them forever is the failure mode the guard exists to prevent.
 */
final class SinkFailureClassifier {

    /** SQLState classes that report a condition expected to clear on its own. */
    private static final Set<String> TRANSIENT_SQL_STATE_CLASSES = Set.of("08", "40", "53", "57");

    /** Guard against a cause chain that cycles rather than terminates. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private SinkFailureClassifier() {}

    /**
     * @param failure the exception thrown by a flush
     * @return {@code true} when the same batch may succeed on a later attempt
     */
    static boolean isTransient(Throwable failure) {
        var current = failure;
        for (var depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException && isTransientState(sqlException.getSQLState())) {
                return true;
            }
            var cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private static boolean isTransientState(String sqlState) {
        return sqlState != null
                && sqlState.length() >= 2
                && TRANSIENT_SQL_STATE_CLASSES.contains(sqlState.substring(0, 2));
    }
}
