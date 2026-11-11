package org.example.gtfsynq.store.adapter.outbound.database;

/**
 * A database operation that could not complete.
 *
 * <p>The cause is the original {@link java.sql.SQLException}, sanitized by {@link SqlLogSanitizer}
 * wherever the driver could echo a large batch statement into the message.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(Throwable cause) {
        super(cause);
    }
}
