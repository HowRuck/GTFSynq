package org.example.sirianalyzer.util;

import com.google.common.hash.Hashing;
import com.google.transit.realtime.GtfsRealtime;
import lombok.NoArgsConstructor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Hashing utilities for GTFS FeedEntity objects using Murmur3 128-bit
 */
@NoArgsConstructor
public final class FeedEntityHashing {

    // Murmur3 uses little-endian byte order
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Compute the state of a GTFS entity
     *
     * @param entity GTFS entity to compute state for
     * @return State of the entity
     */
    public static GtfsEntityState computeState(GtfsRealtime.FeedEntity entity) {
        // Construct key and hash values
        var keyBytes = entity.getIdBytes().toByteArray();
        var keyWrapper = new KeyWrapper(keyBytes, Arrays.hashCode(keyBytes));

        var rawBytes = entity.toByteArray();
        var hashResult = Hashing.murmur3_128()
                .hashBytes(rawBytes)
                .asBytes();

        // Extract longs using VarHandle to skip object overhead
        var h1 = (long) LONG_VIEW.get(hashResult, 0);
        var h2 = (long) LONG_VIEW.get(hashResult, 8);

        return new GtfsEntityState(entity, keyWrapper, h1, h2);
    }

    /**
     * Immutable record for GTFS entity state
     *
     * @param original   Original GTFS entity
     * @param keyWrapper Wrapper for the key
     * @param h1         1st hash value
     * @param h2         2nd hash value
     */
    public record GtfsEntityState(
            GtfsRealtime.FeedEntity original,
            KeyWrapper keyWrapper,
            long h1,
            long h2
    ) {
    }

    /**
     * Wrapper for byte array keys with precomputed hash code for efficient LMDB storage and comparison
     *
     * @param bytes           Bytes of the key
     * @param precomputedHash Precomputed hash code
     */
    public record KeyWrapper(byte[] bytes, int precomputedHash) {

        /**
         * Override hashCode to use precomputed hash code
         *
         * @return Precomputed hash code
         */
        @Override
        public int hashCode() {
            return precomputedHash;
        }

        /**
         * Override equals to compare byte arrays
         *
         * @param obj the reference object with which to compare.
         * @return {@code true} if this object is the same as the obj argument; {@code false} otherwise.
         */
        @Override
        public boolean equals(Object obj) {
            return (obj instanceof KeyWrapper other) && (other.precomputedHash == precomputedHash);
        }
    }
}