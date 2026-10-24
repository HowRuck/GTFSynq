package org.example.gtfsynq.store.service;

/**
 * Defers an acknowledgement until the surrounding transaction has committed.
 *
 * <p>The sink releases a Kafka record only once the batch it belongs to is durable. Routing that
 * through this seam keeps the sink free of JTA types and lets unit tests run acknowledgements
 * inline instead of standing up a transaction manager.
 */
@FunctionalInterface
public interface AckScheduler {

    /**
     * Runs {@code acknowledgement} once the current transaction commits, or immediately when no
     * transaction is active.
     *
     * @param acknowledgement action releasing the Kafka record, run at most once
     */
    void afterCommit(Runnable acknowledgement);
}
