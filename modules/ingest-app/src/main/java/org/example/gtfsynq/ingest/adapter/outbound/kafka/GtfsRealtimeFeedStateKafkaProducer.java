package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.protocol.RealtimeFeedPolled;

/**
 * Kafka producer for GTFS-RT poll outcomes
 * <p>
 * Events carry only HTTP cache validators and the poll status, never feed content, so the
 * feeds table can show realtime feeds beside the static ones
 */
@ApplicationScoped
@Slf4j
public class GtfsRealtimeFeedStateKafkaProducer {

    private final KafkaProducer<String, byte[]> kafka;

    private final String topic;

    public GtfsRealtimeFeedStateKafkaProducer(
            KafkaProducer<String, byte[]> kafka,
            @ConfigProperty(name = "gtfsynq.kafka.realtime-feed-state-topic", defaultValue = "gtfs-realtime-feed-state")
                    String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    /**
     * Publishes a poll outcome, keyed by feed id so one feed's events stay ordered. Failures are
     * logged and swallowed: tracking must never interfere with polling.
     *
     * @param event the poll outcome to publish
     */
    public void send(RealtimeFeedPolled event) {
        try {
            kafka.send(new ProducerRecord<>(topic, event.getFeedId(), event.toByteArray()));
        } catch (RuntimeException e) {
            log.warn("Failed to publish realtime feed state for {}: {}", event.getFeedId(), e.getMessage());
        }
    }
}
