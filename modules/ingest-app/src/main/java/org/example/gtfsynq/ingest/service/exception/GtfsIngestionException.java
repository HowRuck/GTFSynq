package org.example.gtfsynq.ingest.service.exception;

public class GtfsIngestionException extends RuntimeException {
    public GtfsIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
