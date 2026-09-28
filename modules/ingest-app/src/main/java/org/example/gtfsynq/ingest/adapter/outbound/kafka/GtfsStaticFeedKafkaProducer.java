package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka producer for GTFS static feed ingestion events
 * <p>
 * Only the storage location and integrity metadata travel over Kafka; downstream
 * consumers fetch and validate the archive themselves
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedKafkaProducer {

    /**
     * Kafka template for sending messages
     */
    private final KafkaTemplate<String, byte[]> kafka;

    /**
     * Kafka topic static feed ingestion events are published to
     */
    @Value("${spring.kafka.static-feed-topic:gtfs-static-feeds}")
    private String topic;

    /**
     * Publishes a static feed ingestion event, keyed by feed id so that events of one
     * feed stay ordered in a single partition.
     *
     * @param event the ingestion event to publish
     */
    public void send(StaticFeedIngested event) {
        kafka.send(topic, event.getFeedId(), event.toByteArray());

        log.debug("Published static feed ingestion event for {} to topic {}", event.getFeedId(), topic);
    }
}
