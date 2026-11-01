package org.example.gtfsynq.ingest.adapter.inbound.http;

import java.time.Duration;

/**
 * Signals that a static feed origin refused the download with {@code 429 Too Many Requests}.
 *
 * <p>Unlike a generic download failure this is not a fault to log with a stack trace: the origin
 * is explicitly asking the client to come back later. The {@code Retry-After} value, when the
 * origin sent one, says how much later.
 */
public class StaticFeedRateLimitedException extends StaticFeedDownloadException {

    /** Delay the origin asked for, or {@code null} when it sent no usable {@code Retry-After}. */
    private final Duration retryAfter;

    public StaticFeedRateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
