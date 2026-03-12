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
     * Checks if the hash for the specified key has changed
     *
     * @param txn LMDB transaction
     * @param key LMDB key
     * @param h   Hash value to compare with
     * @return True if the hash has changed, false otherwise
     */
    public boolean hasChanged(
            Txn<ByteBuffer> txn, ByteBuffer key, long h
    ) {
        var existing = db.get(txn, key);
        if (existing == null) return true;

        return existing.getLong(0) != h;
    }

    /**
     * Stores the given hash for the specified key in LMDB
     * <p/>
     *
     * @param txn LMDB transaction
     * @param key LMDB key
     * @param h   Hash value to store
     */
    public void putHash(Txn<ByteBuffer> txn, ByteBuffer key, ByteBuffer h) {
        db.put(txn, key, h);
    }

}
