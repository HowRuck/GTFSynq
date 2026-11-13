package org.example.gtfsynq.store.adapter.inbound.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.example.gtfsynq.shared.protocol.RealtimeFeedPolled;
import org.example.gtfsynq.store.adapter.outbound.database.RealtimeFeedStateRepository;

/**
 * Kafka consumer for GTFS-RT poll outcomes. Malformed or failing events are logged and dropped:
 * the next poll supersedes them, so a retry would add nothing.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsRealtimeFeedStateConsumer {

    private final RealtimeFeedStateRepository repository;

    @Incoming("gtfs-realtime-feed-state")
    @Blocking
    public void consume(Record<String, byte[]> record) {
        if (record.value() == null) {
            return;
        }
        try {
            var event = RealtimeFeedPolled.parseFrom(record.value());
            if (event.getFeedId().isEmpty() || event.getSourceUrl().isEmpty()) {
                log.error("Discarding realtime feed state event without a feed id or URL (key {})", record.key());
                return;
            }
            repository.upsert(event);
        } catch (InvalidProtocolBufferException e) {
            log.error("Discarding malformed realtime feed state event (key {})", record.key(), e);
        } catch (RuntimeException e) {
            log.error("Failed to record realtime feed state (key {})", record.key(), e);
        }
    }
}
