package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.example.gtfsynq.shared.model.FeedEntityWithMetadata;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.store.service.GtfsTripUpdateSink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for GTFS-RT TripUpdate messages.
 *
 * <p>This consumer only parses and routes messages into the buffered sink.
 * Actual database persistence is handled by {@link GtfsTripUpdateSink}, which batches
 * and flushes updates to the relational TimescaleDB schema.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GtfsKafkaConsumer {

    private final Serde<String> keySerde;
    private final Serde<byte[]> valueSerde;
    private final GtfsTripUpdateSink tripUpdateSink;

    @Value("${spring.kafka.topic:gtfs-trip-updates}")
    private String topic;

    /**
     * Parses a raw Kafka payload into a GTFS feed entity.
     *
     * <p>Only GTFS TripUpdate entities are accepted. Other entity types are ignored.
     *
     * @param value encoded Kafka value
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
     * Configure the Kafka Streams topology.
     *
     * <p>The stream parses raw values, filters out non-TripUpdate entities, and forwards the
     * result to the sink for batching.
     *
     * @param builder Kafka Streams topology builder
     */
    @Autowired
    public void consume(StreamsBuilder builder) {
        var messageStream = builder.stream(topic, Consumed.with(keySerde, valueSerde));

        messageStream
                .mapValues(this::parseFeedEntity)
                .filter((key, value) -> Objects.nonNull(value))
                .foreach(this::routeToSink);
    }
}
