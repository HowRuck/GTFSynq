package org.example.sirianalyzer.util;

import com.google.transit.realtime.GtfsRealtime;
import lombok.NoArgsConstructor;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

/**
 * Hashing utilities for GTFS FeedEntity objects using Murmur3 128-bit
 */
@NoArgsConstructor
public final class FeedEntityHashing {

    private static final XXHash64 XXHASH = XXHashFactory.fastestInstance().hash64();
    private static final long SEED = 0x9747b28cL;

    /**
     * Compute the state of a GTFS entity
     *
     * @param entity GTFS entity to compute state for
     * @return State of the entity
     */
    public static GtfsEntityState computeState(GtfsRealtime.FeedEntity entity) {
        var keyBytes = entity.getIdBytes().toByteArray();
        var rawBytes = entity.toByteArray();

        var hash = XXHASH.hash(rawBytes, 0, rawBytes.length, SEED);

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