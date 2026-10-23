package org.example.gtfsynq.ui.client;

/** Raised when the console cannot reach or query the gRPC backend. */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
