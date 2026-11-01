package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import com.google.transit.realtime.GtfsRealtime;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;

/**
 * Kafka producer for GTFS trip updates
 */
@ApplicationScoped
@Slf4j
public class GtfsKafkaProducer {

    /**
     * Writes the W3C trace context into record headers so downstream consumers
     * (e.g. {@code store-app}'s {@code @Incoming} listener) join this trace rather
     * than starting a detached root span.
     */
    private static final TextMapSetter<Headers> HEADER_SETTER =
            (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8));

    /**
     * Kafka producer for sending messages
     */
    private final KafkaProducer<String, byte[]> kafka;

    /**
     * Kafka topic to send messages to
     */
    private final String topic;

    private final OpenTelemetry openTelemetry;

    public GtfsKafkaProducer(
            KafkaProducer<String, byte[]> kafka,
            @ConfigProperty(name = "gtfsynq.kafka.trip-updates-topic", defaultValue = "gtfs-trip-updates") String topic,
            OpenTelemetry openTelemetry) {
        this.kafka = kafka;
        this.topic = topic;
        this.openTelemetry = openTelemetry;
    }

    /**
     * Sends a batch of trip updates to Kafka.
     *
     * @param feedId the feed ID
     * @param entities the entities to send
     */
    @WithSpan
    public void sendTripUpdates(String feedId, List<BinaryFeedEntityWithMetadata> entities) {
        var startTime = System.currentTimeMillis();

        if (entities == null || entities.isEmpty()) {
            return;
        }

        log.debug("Sending {} trip updates to Kafka", entities.size());

        var parent = Context.current();
        var propagator = openTelemetry.getPropagators().getTextMapPropagator();

        for (var entity : entities) {
            var headers = new RecordHeaders();
            propagator.inject(parent, headers, HEADER_SETTER);

            // KafkaProducer#send(String, K, V) was removed in Kafka 4.
            kafka.send(new ProducerRecord<>(topic, null, feedId, entity.encode(), headers));
        }

        var endTime = System.currentTimeMillis();
        log.debug("Sent {} trip updates to Kafka in {}ms", entities.size(), endTime - startTime);
    }

    /**
     * Send a GTFS trip update to Kafka
     *
     * @param id ID of the trip update
     * @param entity The GTFS trip update entity
     */
    public void send(String id, GtfsRealtime.FeedEntity entity) {
        // KafkaProducer#send(String, K, V) was removed in Kafka 4.
        kafka.send(new ProducerRecord<>(topic, id, entity.toByteArray()));
    }
}
