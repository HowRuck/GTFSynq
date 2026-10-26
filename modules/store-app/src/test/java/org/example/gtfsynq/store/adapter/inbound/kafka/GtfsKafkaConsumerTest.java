package org.example.gtfsynq.store.adapter.inbound.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.KafkaRecordBatch;
import io.smallrye.reactive.messaging.kafka.Record;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void acknowledgesTheWholeBatchAsSoonAsItIsBuffered() {
        List<KafkaRecord<String, byte[]>> records =
                List.of(KafkaRecord.of("feed", payload(1)), KafkaRecord.of("feed", payload(2)));

        var acknowledged = new AtomicBoolean();

        CompletionStage<Void> acknowledgement = consumer.consume(batchOf(records, acknowledged));

        assertTrue(
                acknowledged.get(),
                "the batch must be acknowledged explicitly: the connector does not acknowledge it, and"
                        + " an unacknowledged batch stalls every later offset until the watchdog fails"
                        + " the channel");
        assertTrue(acknowledgement.toCompletableFuture().isDone());
        assertEquals(0, repository.writtenUpdates, "the records are buffered, not written yet");
        sink.flushNow();
        assertEquals(2, repository.writtenUpdates);
    }

    @SuppressWarnings("unchecked")
    private static KafkaRecordBatch<String, byte[]> batchOf(
            List<KafkaRecord<String, byte[]>> records, AtomicBoolean acknowledged) {
        return (KafkaRecordBatch<String, byte[]>) Proxy.newProxyInstance(
                GtfsKafkaConsumerTest.class.getClassLoader(),
                new Class<?>[] {KafkaRecordBatch.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRecords" -> records;
                    case "getPayload" ->
                        records.stream().map(KafkaRecord::getPayload).toList();
                    case "ack" -> {
                        acknowledged.set(true);
                        yield CompletableFuture.completedFuture(null);
                    }
                    case "nack" -> CompletableFuture.completedFuture(null);
                    case "toString" -> "KafkaRecordBatch";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
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
