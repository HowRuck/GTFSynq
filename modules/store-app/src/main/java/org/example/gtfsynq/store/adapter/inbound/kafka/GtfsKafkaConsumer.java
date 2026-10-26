package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.KafkaRecordBatch;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.example.gtfsynq.shared.model.FeedEntityWithMetadata;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.store.service.GtfsTripUpdateSink;

/**
 * Kafka consumer for GTFS-RT TripUpdate messages.
 *
 * <p>This consumer only parses and routes messages into the buffered sink.
 * Actual database persistence is handled by {@link GtfsTripUpdateSink}, which batches
 * and flushes updates to the relational TimescaleDB schema.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsKafkaConsumer {

    private final GtfsTripUpdateSink tripUpdateSink;

    /**
     * Parses a raw Kafka payload into a GTFS feed entity.
     *
     * <p>Only GTFS TripUpdate entities are accepted. Other entity types are ignored.
     *
     * @param bytes encoded Kafka value
     * @return parsed feed entity, or {@code null} if the payload is invalid or not a TripUpdate
     */
    public FeedEntityWithMetadata parseFeedEntity(byte[] bytes) {
        try {
            var typedEntity = BinaryFeedEntityWithMetadata.decode(bytes);

            var feedEntity = FeedEntity.parseFrom(typedEntity.bytes());

            return new FeedEntityWithMetadata(feedEntity, typedEntity.type(), Instant.ofEpochSecond(typedEntity.ts()));
        } catch (InvalidProtocolBufferException e) {
            log.error("Failed to parse FeedEntity", e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error while parsing FeedEntity", e);
            return null;
        }
    }

    /**
     * Routes a parsed TripUpdate into the buffered sink.
     *
     * @param feedId Kafka key / feed identifier
     * @param entity parsed GTFS feed entity
     */
    public void routeToSink(String feedId, FeedEntityWithMetadata entity) {
        if (entity == null) {
            return;
        }

        tripUpdateSink.accept(feedId, entity);
    }

    /**
     * Consumes a polled batch of raw Kafka records.
     *
     * <p>Records are acknowledged by the connector when the returned stage completes, and the sink
     * only buffers them, so that stage is already complete on return. There is deliberately no
     * deferred acknowledgement: a stuck acknowledgement previously killed the consumer channel via
     * the throttled commit strategy.
     *
     * <p>A batch is itself a message, so the framework accepts only an asynchronous return type
     * here; the returned stage is what it acknowledges.
     *
     * @param batch records polled by the connector, keyed by feed identifier
     * @return a completed stage, acknowledging the batch
     */
    @Incoming("gtfs-trip-updates")
    @Blocking
    public CompletionStage<Void> consume(KafkaRecordBatch<String, byte[]> batch) {
        log.debug("Polled {} gtfs-trip-updates records", batch.getRecords().size());
        for (var record : batch.getRecords()) {
            consumeRecord(record.getKey(), record.getPayload());
        }
        return CompletableFuture.completedFuture(null);
    }

    void consumeRecord(Record<String, byte[]> record) {
        consumeRecord(record.key(), record.value());
    }

    private void consumeRecord(String key, byte[] value) {
        if (value == null) {
            return;
        }
        routeToSink(key, parseFeedEntity(value));
    }
}
