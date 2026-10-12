package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.store.service.GtfsStaticFeedValidationService;
import org.example.gtfsynq.store.service.metrics.StaticValidationMetrics;

/**
 * Kafka consumer for GTFS static feed ingestion events.
 *
 * <p>Only the location and integrity metadata travel over Kafka, so this hands the event to {@link
 * GtfsStaticFeedValidationService}, which fetches the referenced archive, checks its digest and
 * validates it. Every outcome is acknowledged: the work is idempotent and all failures are either
 * properties of the feed itself or reported through metrics, so retrying would only re-fetch the
 * same archive without changing the result.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedConsumer {

    private final GtfsStaticFeedValidationService validationService;

    private final StaticValidationMetrics metrics;

    /**
     * Validates the archive referenced by an ingestion event.
     *
     * @param record raw Kafka record, keyed by feed id
     */
    @Incoming("gtfs-static-feeds")
    @Blocking
    public void consume(Record<String, byte[]> record) {
        var value = record.value();
        if (value == null) {
            return;
        }

        StaticFeedIngested event;
        try {
            event = StaticFeedIngested.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            metrics.recordResult(StaticValidationMetrics.Result.MALFORMED_EVENT);
            log.error("Discarding malformed static feed ingestion event (key {})", record.key(), e);
            return;
        }

        if (event.getFeedId().isEmpty() || event.getObjectKey().isEmpty()) {
            metrics.recordResult(StaticValidationMetrics.Result.MALFORMED_EVENT);
            log.error("Discarding static feed ingestion event without a feed id or object key (key {})", record.key());
            return;
        }

        var outcome = validationService.validate(event);
        log.debug("Static feed {} ({}) validation outcome: {}", event.getFeedId(), event.getObjectKey(), outcome);
    }
}
