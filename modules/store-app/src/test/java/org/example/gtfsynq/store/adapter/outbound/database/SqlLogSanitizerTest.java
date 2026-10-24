package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlLogSanitizerTest {

    private static final String TAIL = " was aborted: ERROR: boom. Detail: Key (id)=(1) is not present. Hint: none.";

    @Test
    void leavesShortMessagesUntouched() {
        var exception = new BatchUpdateException("Batch entry 0 short", "23503", new int[] {1});

        assertSame(exception, SqlLogSanitizer.sanitize(exception));
    }

    @Test
    void elidesTheMiddleOfAnOversizedBatchMessage() {
        var sql = "INSERT INTO rt_stop_time_updates_hot VALUES " + "('value'), ".repeat(20_000);
        var exception = new BatchUpdateException("Batch entry 0 " + sql + TAIL, "23503", new int[] {1, 2});

        var sanitized = SqlLogSanitizer.sanitize(exception);

        assertInstanceOf(BatchUpdateException.class, sanitized);
        assertTrue(sanitized.getMessage().length() < 2 * SqlLogSanitizer.SNIPPET + 300);
        assertTrue(sanitized.getMessage().startsWith("Batch entry 0 "));
        assertTrue(sanitized.getMessage().endsWith(TAIL));
        assertTrue(sanitized.getMessage().contains("chars of rewritten batch SQL elided"));
        assertEquals("23503", sanitized.getSQLState());
        assertArrayEquals(new int[] {1, 2}, ((BatchUpdateException) sanitized).getUpdateCounts());
    }

    @Test
    void keepsShortCauseInstancesAndTruncatesOversizedOnes() {
        var shortCause = new SQLException("duplicate key value violates unique constraint");
        var withShortCause =
                new BatchUpdateException("Batch entry 0 " + "x".repeat(5_000) + TAIL, "23505", null, shortCause);

        assertSame(shortCause, SqlLogSanitizer.sanitize(withShortCause).getCause());

        var longCause = new SQLException("Caused by rewritten SQL " + "y".repeat(5_000) + TAIL);
        var withLongCause =
                new BatchUpdateException("Batch entry 0 " + "x".repeat(5_000) + TAIL, "23505", null, longCause);

        var sanitized = SqlLogSanitizer.sanitize(withLongCause);
        assertTrue(sanitized.getCause() != longCause);
        assertTrue(sanitized.getCause().getMessage().contains("chars of rewritten batch SQL elided"));
        assertTrue(sanitized.getCause().getMessage().endsWith(TAIL));
    }

    @Test
    void preservesTheNextExceptionChainWithoutDuplicatingACause() {
        var next = new SQLException("next " + "z".repeat(5_000) + TAIL);
        var exception = new BatchUpdateException("Batch entry 0 short", "23503", null);
        exception.setNextException(next);

        var sanitized = SqlLogSanitizer.sanitize(exception);
        assertTrue(sanitized.getNextException().getMessage().contains("chars of rewritten batch SQL elided"));

        var shared = new SQLException("shared " + "z".repeat(5_000) + TAIL);
        var both = new SQLException("short", "23503", shared);
        both.setNextException(shared);

        var deduplicated = SqlLogSanitizer.sanitize(both);
        assertNull(deduplicated.getNextException(), "the shared cause must not be repeated as next");
        assertNotSame(shared, deduplicated.getCause());
    }
}
