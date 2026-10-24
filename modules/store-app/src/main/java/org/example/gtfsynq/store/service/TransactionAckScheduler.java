package org.example.gtfsynq.store.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AckScheduler} backed by the JTA transaction.
 *
 * <p>Acknowledging between the write and the commit would release the offset for rows a rollback
 * then discards. The acknowledgement is therefore registered as an interposed synchronization and
 * only runs for {@link Status#STATUS_COMMITTED}. A rolled-back flush leaves its records
 * unacknowledged; Kafka redelivers them, and the sink's deduplication makes the replay harmless.
 */
@Singleton
@Slf4j
class TransactionAckScheduler implements AckScheduler {

    private final TransactionSynchronizationRegistry registry;

    @Inject
    TransactionAckScheduler(TransactionSynchronizationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterCommit(Runnable acknowledgement) {
        if (registry.getTransactionStatus() == Status.STATUS_NO_TRANSACTION) {
            // The early flush runs on the consumer thread without a transaction; the write used
            // autocommit, so the rows are already durable and the record can be released.
            run(acknowledgement);
            return;
        }

        try {
            registry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // no work before completion
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        run(acknowledgement);
                    }
                }
            });
        } catch (RuntimeException e) {
            // Registration fails once the transaction is already completing. Releasing the record
            // here is still safe: the write succeeded, and a released offset is never lost data.
            log.warn("Could not defer acknowledgement to commit; acknowledging now", e);
            run(acknowledgement);
        }
    }

    private static void run(Runnable acknowledgement) {
        try {
            acknowledgement.run();
        } catch (RuntimeException e) {
            log.warn("Failed to acknowledge a flushed TripUpdate", e);
        }
    }
}
