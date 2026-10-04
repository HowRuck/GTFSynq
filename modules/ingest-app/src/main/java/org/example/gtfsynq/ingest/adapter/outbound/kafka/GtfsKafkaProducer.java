package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import com.google.transit.realtime.GtfsRealtime;
import jakarta.inject.Singleton;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;

/**
 * Kafka producer for GTFS trip updates
 */
@Singleton
@Slf4j
public class GtfsKafkaProducer {

    /**
     * Kafka producer for sending messages
     */
    private final KafkaProducer<String, byte[]> kafka;

    /**
     * Kafka topic to send messages to
     */
    private final String topic;

    public GtfsKafkaProducer(
            KafkaProducer<String, byte[]> kafka,
            @ConfigProperty(name = "gtfsynq.kafka.trip-updates-topic", defaultValue = "gtfs-trip-updates")
                    String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    /**
     * Sends a batch of trip updates to Kafka.
     *
     * @param feedId the feed ID
     * @param entities the entities to send
     */
    public void sendTripUpdates(String feedId, List<BinaryFeedEntityWithMetadata> entities) {
        var startTime = System.currentTimeMillis();

        if (entities == null || entities.isEmpty()) {
            return;
        }

        log.debug("Sending {} trip updates to Kafka", entities.size());

        for (var entity : entities) {
            // KafkaProducer#send(String, K, V) was removed in Kafka 4.
            kafka.send(new ProducerRecord<>(topic, feedId, entity.encode()));
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
