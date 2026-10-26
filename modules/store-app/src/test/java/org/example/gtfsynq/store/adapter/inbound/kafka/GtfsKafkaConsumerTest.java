package org.example.gtfsynq.store.adapter.inbound.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.reactive.messaging.kafka.Record;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateBatchMapper;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRepository;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRows;
import org.example.gtfsynq.store.service.DatabaseDeduplicationService;
import org.example.gtfsynq.store.service.GtfsTripUpdateSink;
import org.example.gtfsynq.store.service.metrics.GtfsSinkMetrics;
import org.junit.jupiter.api.Test;

class GtfsKafkaConsumerTest {

    private final FakeRepository repository = new FakeRepository();
    private final GtfsTripUpdateSink sink = new GtfsTripUpdateSink(
            repository,
            new TripUpdateBatchMapper(),
            new PassthroughDeduplication(),
            new GtfsSinkMetrics(new SimpleMeterRegistry()),
            true,
            20_000);

    private final GtfsKafkaConsumer consumer = new GtfsKafkaConsumer(sink);

    @Test
    void acknowledgesImmediatelyButStillBuffersForTheNextFlush() {
        consumer.consumeRecord(Record.of("feed", payload(1)));

        assertEquals(0, repository.writtenUpdates, "the record is buffered, not written yet");
        sink.flushNow();
        assertEquals(1, repository.writtenUpdates);
    }

    @Test
    void dropsAnUnparseablePayloadWithoutBuffering() {
        consumer.consumeRecord(Record.of("feed", new byte[] {1, 2, 3}));

        sink.flushNow();
        assertEquals(0, repository.writtenUpdates);
    }

    private static byte[] payload(long id) {
        var tripUpdate = TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder()
                        .setTripId("trip-" + id)
                        .setRouteId("route")
                        .setStartDate("20261024")
                        .setStartTime("12:00:00"))
                .build();
        var entity = FeedEntity.newBuilder()
                .setId("entity-" + id)
                .setTripUpdate(tripUpdate)
                .build();
        return new BinaryFeedEntityWithMetadata(entity.toByteArray(), 0, 0).encode();
    }

    private static final class FakeRepository extends TripUpdateRepository {

        private int writtenUpdates;

        private FakeRepository() {
            super(null);
        }

        @Override
        public FlushTimings write(TripUpdateRows rows) {
            writtenUpdates += rows.descriptors().size();
            return new FlushTimings(0, 0, 0, 0);
        }
    }

    private static final class PassthroughDeduplication extends DatabaseDeduplicationService {

        private PassthroughDeduplication() {
            super(null);
        }

        @Override
        public TripUpdateDto cleanState(TripUpdateDto entity) {
            return entity;
        }
    }
}
