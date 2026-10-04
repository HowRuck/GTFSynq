package org.example.gtfsynq.ingest.config;

/**
 * Capped HTTP timeouts applied to individual feed requests.
 * <p>
 * Both values are already clamped to the configured realtime feed timeout so that a single
 * request can never outlive its owning poll.
 *
 * @param connectMs connection timeout in milliseconds
 * @param readMs read timeout in milliseconds
 */
public record HttpTimeouts(long connectMs, long readMs) {}
