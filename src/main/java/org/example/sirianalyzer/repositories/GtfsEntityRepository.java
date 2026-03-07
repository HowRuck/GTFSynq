package org.example.sirianalyzer.repositories;

import com.google.transit.realtime.GtfsRealtime;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.util.FeedEntityHashing;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Stores 128-bit (Murmur3_128) hashes of GTFS entities in LMDB, skipping unchanged entries.
 */
@Repository
@AllArgsConstructor
@Slf4j
public class GtfsEntityRepository {

    private static final int HASH_SIZE = 16; // 2 × 8-byte longs

    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> db;

    /**
     * Persists hashes for the given GTFS entities, skipping any whose hash has not changed.
     *
     * @return the number of entities actually written (i.e. new or changed)
     */
    public int storeHashes(List<GtfsRealtime.FeedEntity> entities) {
        long startTime = System.currentTimeMillis();
        log.info("Storing {} GTFS entities into LMDB...", entities.size());

        List<FeedEntityHashing.EntityHash> entityHashes = computeHashes(entities);
        int updated = persistHashes(entityHashes);

        log.info("LMDB update complete: {} updated, {} skipped out of {} entities in {} ms",
                updated, entities.size() - updated, entities.size(), System.currentTimeMillis() - startTime);

        return updated;
    }

    /**
     * Computes hashes for the given GTFS entities
     *
     * @param entities The GTFS entities to compute hashes for
     * @return A list of hashes, one for each entity
     */
    private List<FeedEntityHashing.EntityHash> computeHashes(List<GtfsRealtime.FeedEntity> entities) {
        return entities.parallelStream()
                .map(FeedEntityHashing::fromEntity)
                .toList();
    }

    /**
     * Persists hashes to LMDB, skipping unchanged entries
     *
     * @param entityHashes The hashes to persist
     * @return The number of hashes actually written (i.e. new or changed)
     */
    private int persistHashes(List<FeedEntityHashing.EntityHash> entityHashes) {
        int updated = 0;

        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            for (FeedEntityHashing.EntityHash eh : entityHashes) {
                // Fetch existing hash for this entity key
                var existing = db.get(txn, eh.key());
                var eh1 = eh.h1();
                var eh2 = eh.h2();

                // Skip unchanged entries
                if (existing != null) {
                    var ex1 = existing.getLong();
                    var ex2 = existing.getLong();

                    if (ex1 == eh1 && ex2 == eh2) {
                        continue;
                    }
                }

                // Write new or changed entries
                var value = ByteBuffer.allocateDirect(HASH_SIZE)
                        .putLong(eh1)
                        .putLong(eh2)
                        .flip();

                db.put(txn, eh.key(), value);
                updated++;
            }

            txn.commit();

        } catch (Exception ex) {
            log.error("Failed to commit GTFS entity hashes to LMDB", ex);
        }

        return updated;
    }
}