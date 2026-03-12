package org.example.sirianalyzer.repositories;

import lombok.AllArgsConstructor;
import org.lmdbjava.Dbi;
import org.lmdbjava.Txn;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;

/**
 * Repository for managing GTFS state data using LMDB
 */
@AllArgsConstructor
@Repository
public class GtfsStateRepository {

    /**
     * LMDB database handle for GTFS state data
     */
    private final Dbi<ByteBuffer> db;
    /**
     * Size of the hash value in bytes
     */
    private static final int HASH_SIZE = 16;

    /**
     * Checks if the hash for the specified key has changed
     *
     * @param txn LMDB transaction
     * @param key LMDB key
     * @param h1 1st hash value
     * @param h2 2nd hash value
     * @return True if the hash has changed, false otherwise
     */
    public boolean hasChanged(
            Txn<ByteBuffer> txn, ByteBuffer key, long h1, long h2
    ) {
        var existing = db.get(txn, key);
        if (existing == null) return true;

        return existing.getLong() != h1 || existing.getLong() != h2;
    }

    /**
     * Stores the given hash for the specified key in LMDB
     * <p/>
     * The hash is represented as two longs (h1, h2) and stored as a 16-byte value
     *
     * @param txn LMDB transaction
     * @param key LMDB key
     * @param h1 1st hash value
     * @param h2 2nd hash value
     */
    public void putHash(Txn<ByteBuffer> txn, ByteBuffer key, long h1, long h2) {
        var val = ByteBuffer.allocateDirect(HASH_SIZE)
                .putLong(h1)
                .putLong(h2)
                .flip();

        db.put(txn, key, val);
    }

}
