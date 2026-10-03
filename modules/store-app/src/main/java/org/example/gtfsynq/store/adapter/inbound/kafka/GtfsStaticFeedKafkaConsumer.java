package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.store.adapter.inbound.storage.S3StaticFeedArchiveReader;
import org.example.gtfsynq.store.adapter.inbound.storage.S3StaticFeedArchiveReader.FetchedArchive;
import org.example.gtfsynq.store.service.GtfsStaticFeedValidator;
import org.example.gtfsynq.store.service.metrics.StaticFeedValidationMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes GTFS static feed ingestion events and validates the archives they point at.
 * <p>
 * The event itself is only a pointer: bucket, object key and integrity metadata. The
 * archive is fetched from object storage and handed to the canonical GTFS Schedule
 * validator, because the archive never travels over Kafka.
 * <p>
 * This runs on a dedicated consumer group rather than sharing the trip update group's.
 * Both listeners live in one application, and two subscriptions under a single group id
 * would have their partitions reassigned between them on every rebalance.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "gtfsynq.static-validation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GtfsStaticFeedKafkaConsumer {

    private final S3StaticFeedArchiveReader archiveReader;

    private final GtfsStaticFeedValidator validator;

    private final StaticFeedValidationMetrics metrics;

    /**
     * Consumes a raw static feed ingestion event.
     *
     * @param value raw Kafka value holding a serialized {@link StaticFeedIngested}
     */
    @KafkaListener(
            topics = "${spring.kafka.static-feed-topic:gtfs-static-feeds}",
            groupId = "${gtfsynq.static-validation.consumer-group:gtfsynq-store-static-feeds}")
    public void consume(@Payload(required = false) byte[] value) {
        if (value == null) {
            return;
        }

        StaticFeedIngested event;
        try {
            event = StaticFeedIngested.parseFrom(value);
        } catch (InvalidProtocolBufferException e) {
            // Not retryable: no amount of redelivery will make these bytes parse.
            log.error("Discarding undecodable static feed ingestion event of {} bytes", value.length, e);
            return;
        }

        validate(event);
    }

    private void validate(StaticFeedIngested event) {
        var feedId = event.getFeedId();
        var startedAt = System.nanoTime();

        FetchedArchive archive = null;
        try {
            archive = archiveReader.fetch(event);

            var result = validator.validate(feedId, archive);
            metrics.recordResult(result);

            if (result.valid()) {
                log.info("Static feed {} passed validation: {}", feedId, result.summary());
            } else {
                log.warn("Static feed {} failed validation: {}", feedId, result.summary());
            }
        } catch (Exception e) {
            metrics.recordFailure(feedId);
            log.error("Static feed {} could not be validated", feedId, e);
        } finally {
            archiveReader.discard(archive);
            metrics.recordDuration(feedId, System.nanoTime() - startedAt);
        }
    }
}
