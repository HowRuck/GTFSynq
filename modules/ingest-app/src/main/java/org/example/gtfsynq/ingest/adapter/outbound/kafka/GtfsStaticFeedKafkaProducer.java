package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;

/**
 * Kafka producer for GTFS static feed ingestion events
 * <p>
 * Only the storage location and integrity metadata travel over Kafka; downstream
 * consumers fetch and validate the archive themselves
 */
@Singleton
@Slf4j
public class GtfsStaticFeedKafkaProducer {

    /**
     * Kafka producer for sending messages
     */
    private final KafkaProducer<String, byte[]> kafka;

    /**
     * Kafka topic static feed ingestion events are published to
     */
    private final String topic;

    public GtfsStaticFeedKafkaProducer(
            KafkaProducer<String, byte[]> kafka,
            @ConfigProperty(name = "gtfsynq.kafka.static-feeds-topic", defaultValue = "gtfs-static-feeds")
                    String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    /**
     * Publishes a static feed ingestion event, keyed by feed id so that events of one
     * feed stay ordered in a single partition.
     *
     * @param event the ingestion event to publish
     */
    public void send(StaticFeedIngested event) {
        // KafkaProducer#send(String, K, V) was removed in Kafka 4.
        kafka.send(new ProducerRecord<>(topic, event.getFeedId(), event.toByteArray()));

        log.debug("Published static feed ingestion event for {} to topic {}", event.getFeedId(), topic);
    }
}
