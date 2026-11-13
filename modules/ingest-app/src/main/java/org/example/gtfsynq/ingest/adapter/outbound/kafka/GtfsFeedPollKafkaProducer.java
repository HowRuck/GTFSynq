package org.example.gtfsynq.ingest.adapter.outbound.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedValidators;
import org.example.gtfsynq.shared.protocol.FeedKind;
import org.example.gtfsynq.shared.protocol.FeedPolled;
import org.example.gtfsynq.shared.protocol.PollStatus;

/**
 * Kafka producer for poll outcomes of static and realtime feeds
 * <p>
 * Events carry only the poll status, the HTTP cache validators and the body digest, never feed
 * content, so the feeds table can show both kinds of feed side by side
 */
@ApplicationScoped
@Slf4j
public class GtfsFeedPollKafkaProducer {

    private final KafkaProducer<String, byte[]> kafka;

    private final String topic;

    public GtfsFeedPollKafkaProducer(
            KafkaProducer<String, byte[]> kafka,
            @ConfigProperty(name = "gtfsynq.kafka.feed-polls-topic", defaultValue = "gtfs-feed-polls") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    /**
     * Publishes the outcome of one poll.
     *
     * @param kind kind of feed that was polled
     * @param feedId feed source identifier
     * @param sourceUrl URL that was polled
     * @param status outcome of the poll
     * @param sizeBytes size of the downloaded body, 0 when none was transferred
     * @param sha256 hex SHA-256 of the downloaded body, or {@code null} when none was transferred
     * @param validators validators the origin sent, or {@code null} when the poll failed
     */
    public void publish(
            FeedKind kind,
            String feedId,
            String sourceUrl,
            PollStatus status,
            long sizeBytes,
            String sha256,
            StaticFeedValidators validators) {
        var event = FeedPolled.newBuilder()
                .setKind(kind)
                .setFeedId(feedId)
                .setSourceUrl(sourceUrl)
                .setPolledAtEpochSeconds(Instant.now().getEpochSecond())
                .setStatus(status)
                .setSizeBytes(sizeBytes);
        if (sha256 != null) {
            event.setSha256(sha256);
        }
        if (validators != null && validators.etag() != null) {
            event.setSourceEtag(validators.etag());
        }
        if (validators != null && validators.lastModified() != null) {
            event.setSourceLastModified(validators.lastModified());
        }
        send(event.build());
    }

    /**
     * Publishes a poll outcome, keyed by feed id so one feed's events stay ordered. Failures are
     * logged and swallowed: tracking must never interfere with polling.
     *
     * @param event the poll outcome to publish
     */
    public void send(FeedPolled event) {
        try {
            kafka.send(new ProducerRecord<>(topic, event.getFeedId(), event.toByteArray()));
        } catch (RuntimeException e) {
            log.warn("Failed to publish realtime feed state for {}: {}", event.getFeedId(), e.getMessage());
        }
    }
}
