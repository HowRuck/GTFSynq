package org.example.sirianalyzer.services;

import com.google.transit.realtime.GtfsRealtime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.repositories.GtfsStateRepository;
import org.example.sirianalyzer.util.FeedEntityHashing;
import org.lmdbjava.Env;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.List;

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
     * Sync GTFS feed with Kafka
     *
     * @param feedEntities List of GTFS entities to sync
     */
    public void syncFeed(List<GtfsRealtime.FeedEntity> feedEntities) {
        var startTime = System.currentTimeMillis();

        log.info("Received {} entities to sync", feedEntities.size());

        // Compute state for each entity
        var entityStates = feedEntities.parallelStream()
                .map(FeedEntityHashing::computeState)
                .toList();

        var stateDuration = System.currentTimeMillis() - startTime;
        log.info("Computed state for {} entities in {}ms", entityStates.size(), stateDuration);

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

                    stateRepo.putHash(txn, keyBuf, eh1, eh2);
                    updated++;
                }
            }

            txn.commit();

            var totalDuration = System.currentTimeMillis() - startTime;
            var kafkaDuration = System.currentTimeMillis() - startTime - stateDuration;

            log.info("Sent {} trip updates to Kafka in {}ms", updated, kafkaDuration);
            log.info("Total sync duration: {}ms", totalDuration);
        }
    }

}



