package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import com.google.transit.realtime.GtfsRealtime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Kafka producer for GTFS trip updates
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsKafkaProducer {

    /**
     * Kafka template for sending messages
     */
    private final KafkaTemplate<String, byte[]> kafka;

    /**
     * Kafka topic to send messages to
     */
    @Value("${spring.kafka.topic:gtfs-trip-updates}")
    private String topic;

    /**
     * Sends a batch of trip updates to Kafka.
     *
     * @param feedId the feed ID
     * @param entities the entities to send
     */
    @Async
    public void sendTripUpdates(String feedId, List<BinaryFeedEntityWithMetadata> entities) {
        var startTime = System.currentTimeMillis();

        if (entities == null || entities.isEmpty()) {
            return;
        }

        log.debug("Sending {} trip updates to Kafka", entities.size());

        for (var entity : entities) {
            kafka.send(topic, feedId, entity.encode());
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
        kafka.send(topic, id, entity.toByteArray());
    }
}
