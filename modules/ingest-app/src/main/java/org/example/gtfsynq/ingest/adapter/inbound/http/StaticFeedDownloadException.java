package org.example.gtfsynq.ingest.adapter.inbound.http;

/**
 * Signals that a static feed archive could not be downloaded
 */
public class StaticFeedDownloadException extends RuntimeException {

    public StaticFeedDownloadException(String message) {
        super(message);
    }

    public StaticFeedDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
