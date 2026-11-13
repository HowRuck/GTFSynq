package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.example.gtfsynq.shared.protocol.FeedKind;
import org.example.gtfsynq.shared.protocol.FeedPolled;
import org.example.gtfsynq.store.adapter.outbound.database.FeedPollStateRepository;

/**
 * Kafka consumer for poll outcomes of static and realtime feeds. Malformed or failing events are logged and dropped:
 * the next poll supersedes them, so a retry would add nothing.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsFeedPollConsumer {

    private final FeedPollStateRepository repository;

    @Incoming("gtfs-feed-polls")
    @Blocking
    public void consume(Record<String, byte[]> record) {
        if (record.value() == null) {
            return;
        }
        try {
            var event = FeedPolled.parseFrom(record.value());
            if (event.getFeedId().isEmpty() || event.getSourceUrl().isEmpty()) {
                log.error("Discarding feed poll event without a feed id or URL (key {})", record.key());
                return;
            }
            if (event.getKind() == FeedKind.FEED_KIND_STATIC) {
                repository.recordStaticPoll(event);
            } else {
                repository.upsert(event);
            }
        } catch (InvalidProtocolBufferException e) {
            log.error("Discarding malformed realtime feed state event (key {})", record.key(), e);
        } catch (RuntimeException e) {
            log.error("Failed to record realtime feed state (key {})", record.key(), e);
        }
    }
}
