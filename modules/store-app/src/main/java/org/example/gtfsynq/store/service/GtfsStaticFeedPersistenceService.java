package org.example.gtfsynq.store.service;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.shared.util.SizeFormat;
import org.example.gtfsynq.shared.validation.GtfsStaticArchiveReader;
import org.example.gtfsynq.store.adapter.outbound.database.StaticFeedRepository;
import org.example.gtfsynq.store.adapter.outbound.database.StaticFeedWriter;
import org.example.gtfsynq.store.config.StaticPersistenceProperties;
import org.example.gtfsynq.store.service.metrics.StaticPersistenceMetrics;

/**
 * Writes a validated static archive into the database.
 *
 * <p>Runs only after {@link GtfsStaticFeedValidationService} accepted the archive, so the rows it
 * streams have already cleared validation: referential integrity, duplicate keys and cell formats
 * were checked against this exact archive, and the validator's row shapes are what the writer binds.
 * That is why the write is a straight copy rather than a re-checking import.
 *
 * <p>The archive is streamed entry by entry into one transaction that replaces the feed's previous
 * revision, so neither side ever holds a whole feed in memory and the feed is either fully at the
 * new revision or fully at the old one. A redelivered event for the revision already loaded is
 * recognised by its digest, and its archive is not parsed at all.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedPersistenceService {

    private final StaticFeedRepository repository;

    private final StaticPersistenceProperties properties;

    private final StaticPersistenceMetrics metrics;

    private final GtfsStaticArchiveReader reader = new GtfsStaticArchiveReader();

    /** What happened to a validated archive, and so what the database now holds for the feed. */
    public enum Outcome {
        LOADED,
        UNCHANGED,
        FAILED,
        DISABLED
    }

    /**
     * Persists the revision an archive carries.
     *
     * @param event ingestion event the archive was staged from
     * @param archive the validated archive, still on local disk and owned by the caller
     * @return the outcome; only {@link Outcome#LOADED} and {@link Outcome#UNCHANGED} leave the feed
     *     at the revision the event describes
     */
    public Outcome persist(StaticFeedIngested event, Path archive) {
        if (!properties.enabled()) {
            log.debug("Static feed persistence is disabled, keeping the current revision of {}", event.getFeedId());
            metrics.recordResult(event.getFeedId(), StaticPersistenceMetrics.Result.DISABLED);
            return Outcome.DISABLED;
        }

        var start = System.nanoTime();
        log.info(
                "Persisting static feed {} revision {} from {}",
                event.getFeedId(),
                event.getSha256() == null || event.getSha256().length() <= 12
                        ? event.getSha256()
                        : event.getSha256().substring(0, 12),
                event.getObjectKey());
        try (var writer = repository.openRevision(event)) {
            if (!writer.unchanged()) {
                reader.read(archive, properties.batchSize(), writer);
            }
            writer.commit();
            var result = result(writer);
            metrics.recordResult(event.getFeedId(), result);
            metrics.recordRows(event.getFeedId(), writer.rows(), writer.rowsByTable());
            if (log.isDebugEnabled() && !writer.unchanged()) {
                log.debug("Static feed {} wrote {} rows: {}", event.getFeedId(), writer.rows(), writer.rowsByTable());
            }
            logRevision(event, writer);
            return result == StaticPersistenceMetrics.Result.UNCHANGED ? Outcome.UNCHANGED : Outcome.LOADED;
        } catch (IOException | RuntimeException e) {
            // The writer rolls back on close, so the feed stays at its previous revision. The event
            // is acknowledged anyway: a database fault is not fixed by re-fetching the archive.
            metrics.recordResult(event.getFeedId(), StaticPersistenceMetrics.Result.FAILED);
            log.error(
                    "Static feed {} ({}) could not be persisted, keeping the previous revision",
                    event.getFeedId(),
                    event.getObjectKey(),
                    e);
            return Outcome.FAILED;
        } finally {
            metrics.recordLoad(System.nanoTime() - start);
        }
    }

    private static StaticPersistenceMetrics.Result result(StaticFeedWriter writer) {
        return writer.unchanged() ? StaticPersistenceMetrics.Result.UNCHANGED : StaticPersistenceMetrics.Result.LOADED;
    }

    private void logRevision(StaticFeedIngested event, StaticFeedWriter writer) {
        if (writer.unchanged()) {
            log.info(
                    "Static feed {} ({}) is already the loaded revision, nothing rewritten",
                    event.getFeedId(),
                    event.getSha256());
            return;
        }
        log.info(
                "Static feed {} ({}) of size {} replaced its revision with {} rows across {} files",
                event.getFeedId(),
                event.getSha256(),
                SizeFormat.humanBytes(event.getSizeBytes()),
                SizeFormat.formatNumber(writer.rows()),
                writer.rowsByTable().size());
    }
}
