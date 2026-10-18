package org.example.gtfsynq.store.service;

import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.store.service.metrics.StaticIngestionMetrics;

/**
 * Runs a GTFS static feed revision through its stages: fetch, validate, persist.
 *
 * <p>The order is the contract of this class. Validation gates persistence, so a feed only reaches
 * the database once it is known good, and the staged archive is released only after the stage that
 * needed it is done - the persistence stage reads the same bytes the validator accepted, so nothing
 * has to be fetched or re-verified between them.
 *
 * <p>Every outcome ends in a return rather than an exception, and the caller acknowledges the event
 * either way: the stages are idempotent, so redelivery re-fetches an unchanged archive and reaches
 * the same conclusion. The database is left holding either the new revision in full or the previous
 * one, never a mixture.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedIngestionService {

    private final GtfsStaticFeedValidationService validationService;

    private final GtfsStaticFeedPersistenceService persistenceService;

    private final StaticIngestionMetrics metrics;

    /**
     * Takes one ingestion event through fetch, validation and persistence.
     *
     * @param event ingestion event naming the archive in object storage
     */
    public void ingest(StaticFeedIngested event) {
        var sample = metrics.start();
        var outcome = "error";
        Path archive = null;
        try {
            var validation = validationService.stage(event);
            archive = validation.archive();
            if (!validation.valid()) {
                outcome = validation.outcome().name().toLowerCase(Locale.ROOT);
                return;
            }
            var persistenceOutcome = persistenceService.persist(event, archive);
            outcome = persistenceOutcome.name().toLowerCase(Locale.ROOT);
            log.debug(
                    "Static feed {} ({}) validation {} and persistence {}",
                    event.getFeedId(),
                    event.getObjectKey(),
                    validation.outcome(),
                    persistenceOutcome);
        } finally {
            try {
                validationService.discard(archive);
            } finally {
                metrics.complete(sample, outcome);
            }
        }
    }
}
