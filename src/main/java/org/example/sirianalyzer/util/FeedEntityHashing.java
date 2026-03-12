package org.example.sirianalyzer.util;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.transit.realtime.GtfsRealtime;
import lombok.NoArgsConstructor;

/**
 * Hashing utilities for GTFS FeedEntity objects using Murmur3 128-bit
 */
@NoArgsConstructor
public final class FeedEntityHashing {

    private static final HashFunction STATE_HASHER = Hashing.farmHashFingerprint64();

    /**
     * Compute the state of a GTFS entity
     *
     * @param entity GTFS entity to compute state for
     * @return State of the entity
     */
    public static GtfsEntityState computeState(GtfsRealtime.FeedEntity entity) {
        var keyBytes = entity.getIdBytes().toByteArray();
        var rawBytes = entity.toByteArray();

        var hash = STATE_HASHER.hashBytes(rawBytes).asLong();

        return new GtfsEntityState(entity, keyBytes, hash);
    }

    /**
     * Immutable record for GTFS entity state
     *
     * @param original   Original GTFS entity
     * @param keyBytes   Key bytes for the entity
     * @param hash    Hash of the entity's raw bytes
     */
    public record GtfsEntityState(
            GtfsRealtime.FeedEntity original,
            byte[] keyBytes,
            long hash
    ) {
    }
}