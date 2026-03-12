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

    /**
     * Repository for managing GTFS state data using LMDB
     */
    private final GtfsStateRepository stateRepo;
    /**
     * Kafka producer for sending GTFS trip updates
     */
    private final GtfsKafkaProducer kafkaProducer;
    /**
     * LMDB environment handle
     */
    private final Env<ByteBuffer> env;
    /**
     * Meter registry for metrics
     */
    private final MeterRegistry meterRegistry;

    /**
     * Counter for tracking the number of GTFS entities processed
     */
    private Counter totalEntitiesProcessedCounter;
    /**
     * Timer for computing state for GTFS entities
     */
    private Timer stateComputationTimer;
    /**
     * Metrics for Kafka updates
     */
    private Counter kafkaUpdatesCounter;
    /**
     * Timer for sending GTFS trip updates to Kafka
     */
    private Timer kafkaUpdateTimer;
    /**
     * Gauge for tracking the number of entities processed in the current run
     */
    private AtomicInteger entitiesProcessedInCurrentRun;

    @PostConstruct
    public void init() {
        // Initialize metrics after meterRegistry is ready
        totalEntitiesProcessedCounter = Counter.builder("gtfs.entities.total.processed")
                .description("Total number of GTFS entities processed")
                .register(meterRegistry);

        stateComputationTimer = Timer.builder("gtfs.state.computation")
                .description("Time taken to compute state for GTFS entities")
                .register(meterRegistry);

        kafkaUpdatesCounter = Counter.builder("gtfs.kafka.updates")
                .description("Number of GTFS trip updates sent to Kafka")
                .register(meterRegistry);

        kafkaUpdateTimer = Timer.builder("gtfs.kafka.update")
                .description("Time taken to send GTFS trip updates to Kafka")
                .register(meterRegistry);

        entitiesProcessedInCurrentRun = new AtomicInteger(0);
        Gauge.builder("gtfs.entities.processed.per.run", entitiesProcessedInCurrentRun::get)
                .description("Number of GTFS entities processed in the current run")
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

        var stateDuration = System.currentTimeMillis() - startTime;
        log.info("Computed state for {} entities in {}ms", entityStates.size(), stateDuration);

        stateComputationTimer.record(stateDuration, TimeUnit.MILLISECONDS);

        // Write updates to LMDB and send changed entities to Kafka
        try (var txn = env.txnWrite()) {
            int updated = 0;

            for (var entityState : entityStates) {
                var keyBytes = entityState.keyWrapper().bytes();

                var keyBuf = ByteBuffer.allocateDirect(keyBytes.length)
                        .put(keyBytes)
                        .flip();

                var eh1 = entityState.h1();
                var eh2 = entityState.h2();

                if (stateRepo.hasChanged(txn, keyBuf, eh1, eh2)) {
                    kafkaProducer.send(
                            entityState.original().getId(), entityState.original()
                    );
                    kafkaUpdatesCounter.increment();
                    stateRepo.putHash(txn, keyBuf, eh1, eh2);
                    updated++;
                }
            }

            txn.commit();

            var totalDuration = System.currentTimeMillis() - startTime;
            var kafkaDuration = System.currentTimeMillis() - startTime - stateDuration;
            kafkaUpdateTimer.record(kafkaDuration, TimeUnit.MILLISECONDS);

            log.info("Sent {} trip updates to Kafka in {}ms", updated, kafkaDuration);
            log.info("Total sync duration: {}ms", totalDuration);
        }
    }

}



