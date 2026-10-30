package org.example.gtfsynq.ui.client;

/** Raised when the console cannot reach or query VictoriaMetrics. */
public class MetricsException extends RuntimeException {

    public MetricsException(String message) {
        super(message);
    }

    public MetricsException(String message, Throwable cause) {
        super(message, cause);
    }
}
