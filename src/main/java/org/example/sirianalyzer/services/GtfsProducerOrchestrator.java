package org.example.sirianalyzer.services;

import com.google.transit.realtime.GtfsRealtime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.repositories.GtfsStateRepository;
import org.example.sirianalyzer.util.FeedEntityHashing;
import org.lmdbjava.Env;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the process of producing GTFS trip updates to Kafka
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GtfsProducerOrchestrator {

    /** Repository for managing GTFS state data using LMDB */
    private final GtfsStateRepository stateRepo;
    /** Kafka producer for sending GTFS trip updates */
    private final GtfsKafkaProducer kafkaProducer;
    /** LMDB environment handle */
    private final Env<ByteBuffer> env;
    /** Meter registry for metrics */
    private final MeterRegistry meterRegistry;

    /** Counter for tracking the number of GTFS entities processed */
    private Counter totalEntitiesProcessedCounter;
    /** Timer for computing state for GTFS entities */
    private Timer stateComputationTimer;
    /** Timer for checking and updating state in LMDB */
    private Timer lmdbUpdateTimer;

    private Counter kafkaUpdatesCounter;
    /** Timer for sending GTFS trip updates to Kafka */
    private Timer kafkaUpdateTimer;
    /** Gauge for tracking the number of entities processed in the current run */
    private AtomicInteger entitiesProcessedInCurrentRun;

    /** Reusable key buffer for LMDB operations */
    private final ThreadLocal<ByteBuffer> keyBufThreadLocal =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(256));

    /** Reusable value buffer for LMDB operations */
    private final ThreadLocal<ByteBuffer> valBufThreadLocal =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(Long.BYTES));

    /**
     * Initialize metrics and other resources after the application context is initialized
     */
    @PostConstruct
    public void init() {
        var feedName = "gtfs-rt";

        totalEntitiesProcessedCounter = Counter.builder("gtfs.entities.processed")
                .description("Total number of GTFS entities processed")
                .tag("feed", feedName)
                .register(meterRegistry);

        stateComputationTimer = Timer.builder("gtfs.state.computation.duration")
                .description("Time taken to compute state for GTFS entities")
                .tag("feed", feedName)
                .register(meterRegistry);

        // Register the LMDB timer
        lmdbUpdateTimer = Timer.builder("gtfs.lmdb.write.duration")
                .description("Time taken to check and update state in LMDB")
                .tag("feed", feedName)
                .register(meterRegistry);

        kafkaUpdatesCounter = Counter.builder("gtfs.kafka.messages.sent")
                .description("Number of GTFS trip updates sent to Kafka")
                .tag("feed", feedName)
                .register(meterRegistry);

        kafkaUpdateTimer = Timer.builder("gtfs.kafka.send.duration")
                .description("Time taken to send GTFS trip updates to Kafka")
                .tag("feed", feedName)
                .register(meterRegistry);

        entitiesProcessedInCurrentRun = new AtomicInteger(0);
        Gauge.builder("gtfs.entities.processed.current", entitiesProcessedInCurrentRun::get)
                .description("Number of GTFS entities processed in the current run")
                .tag("feed", feedName)
                .register(meterRegistry);
    }

    /**
     * Sync GTFS feed with Kafka
     *
     * @param feedEntities List of GTFS entities to sync
     */
    public void syncFeed(List<GtfsRealtime.FeedEntity> feedEntities) {
        var numEntities = feedEntities.size();

        var startTime = System.currentTimeMillis();
        log.info("Received {} entities to sync", numEntities);

        // Record the number of entities processed
        totalEntitiesProcessedCounter.increment(numEntities);
        entitiesProcessedInCurrentRun.set(numEntities);

        // Compute state for each entity
        var entityStates = feedEntities.parallelStream()
                .map(FeedEntityHashing::computeState)
                .toList();

        var stateEndTime = System.currentTimeMillis();
        var stateDuration = stateEndTime - startTime;

        log.info("Computed state for {} entities in {}ms",
                entityStates.size(), stateDuration);
        stateComputationTimer.record(stateDuration, TimeUnit.MILLISECONDS);

        // Update LMDB with new hashes and check for changes
        var keyBuf = keyBufThreadLocal.get();
        var valBuf = valBufThreadLocal.get();
        // Preallocate list to avoid resizing during additions
        var updatesToSend = new ArrayList<GtfsRealtime.FeedEntity>(entityStates.size());

        try (var txn = env.txnWrite()) {
            for (var entityState : entityStates) {
                var keyBytes = entityState.keyBytes();

                // Reuse the buffer for key to avoid unnecessary allocations
                keyBuf.clear();
                keyBuf.put(keyBytes).flip();

                var eh = entityState.hash();

                if (stateRepo.hasChanged(txn, keyBuf, eh)) {
                    updatesToSend.add(entityState.original());

                    valBuf.clear();
                    valBuf.putLong(eh).flip();

                    stateRepo.putHash(txn, keyBuf, valBuf);
                }
            }
            txn.commit();
        } finally {
            // Clean up thread-local buffers
            keyBufThreadLocal.remove();
            valBufThreadLocal.remove();
        }

        var lmdbEndTime = System.currentTimeMillis();
        var lmdbDuration = lmdbEndTime - stateEndTime;

        log.info("Processed LMDB state checks and updates in {}ms. Identified {} changed entities.",
                lmdbDuration, updatesToSend.size());
        lmdbUpdateTimer.record(lmdbDuration, TimeUnit.MILLISECONDS);

        // Send Updates to Kafka
        for (var entity : updatesToSend) {
            kafkaProducer.send(entity.getId(), entity);
            kafkaUpdatesCounter.increment();
        }

        var kafkaEndTime = System.currentTimeMillis();
        var kafkaDuration = kafkaEndTime - lmdbEndTime;

        log.info("Sent {} trip updates to Kafka in {}ms",
                updatesToSend.size(), kafkaDuration);
        kafkaUpdateTimer.record(kafkaDuration, TimeUnit.MILLISECONDS);

        var totalDuration = kafkaEndTime - startTime;
        log.info("Total sync duration: {}ms", totalDuration);
    }
}