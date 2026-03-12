package org.example.sirianalyzer.services;

import com.google.transit.realtime.GtfsRealtime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka producer for GTFS trip updates
 */
@Service
@RequiredArgsConstructor
public class GtfsKafkaProducer {
    /**
     * Kafka template for sending messages
     */
    private final KafkaTemplate<String, byte[]> kafka;
    /**
     * Kafka topic to send messages to
     */
    @Value("${spring.kafka.topic}")
    private final String topic = "gtfs-trip-updates";

    /**
     * Send a GTFS trip update to Kafka
     *
     * @param id ID of the trip update
     * @param entity The GTFS trip update entity
     */
    public void send(String id, GtfsRealtime.FeedEntity entity) {
        kafka.send(topic, id, entity.toByteArray());
    }
}
