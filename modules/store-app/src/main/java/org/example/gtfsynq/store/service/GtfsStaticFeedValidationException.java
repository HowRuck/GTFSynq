package org.example.gtfsynq.store.service;

/**
 * Signals that the GTFS validator could not be run to completion, as opposed to running
 * successfully and reporting problems with the feed. A feed that fails validation throws
 * no exception: it produces a {@link StaticFeedValidationResult} with {@code valid} false.
 */
public class GtfsStaticFeedValidationException extends RuntimeException {

    public GtfsStaticFeedValidationException(String message) {
        super(message);
    }

    public GtfsStaticFeedValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
