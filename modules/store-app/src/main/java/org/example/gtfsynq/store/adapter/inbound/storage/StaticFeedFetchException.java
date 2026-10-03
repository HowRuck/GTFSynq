package org.example.gtfsynq.store.adapter.inbound.storage;

/**
 * Signals that a static feed archive referenced by an ingestion event could not be
 * fetched, or did not match the digest the event advertised.
 */
public class StaticFeedFetchException extends RuntimeException {

    public StaticFeedFetchException(String message) {
        super(message);
    }

    public StaticFeedFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
