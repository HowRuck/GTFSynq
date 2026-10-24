package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import io.smallrye.common.annotation.Blocking;
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
     * @param onPersisted acknowledgement releasing the record once the update is durable; run
     *     immediately when the entity is discarded
     */
    public void routeToSink(String feedId, FeedEntityWithMetadata entity, Runnable onPersisted) {
        if (entity == null) {
            onPersisted.run();
            return;
        }

        tripUpdateSink.accept(feedId, entity, onPersisted);
    }

    /**
     * Consume raw Kafka records.
     *
     * <p>The returned stage is the record's acknowledgement: it completes only once the sink has
     * written and committed the batch holding this update. Completing it here rather than letting
     * the method return keeps the offset uncommitted while the update is still buffered, so a crash
     * replays it instead of losing it.
     *
     * @param record raw Kafka record, keyed by feed identifier
     * @return stage completed when the record may be acknowledged
     */
    @Incoming("gtfs-trip-updates")
    @Blocking
    public CompletionStage<Void> consume(Record<String, byte[]> record) {
        var value = record.value();
        if (value == null) {
            return CompletableFuture.completedFuture(null);
        }

        var acknowledged = new CompletableFuture<Void>();
        routeToSink(record.key(), parseFeedEntity(value), () -> acknowledged.complete(null));
        return acknowledged;
    }
}
