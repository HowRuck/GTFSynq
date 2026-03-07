package org.example.sirianalyzer.util;

import com.google.common.hash.Hashing;
import com.google.transit.realtime.GtfsRealtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Hashing utilities for GTFS FeedEntity objects using Murmur3 128-bit.
 */
public final class FeedEntityHashing {

    private FeedEntityHashing() {}

    /**
     * Produces an {@link EntityHash} for a single {@link GtfsRealtime.FeedEntity}.
     * The key is the entity's UTF-8 ID bytes; the hash is stored as two longs (h1, h2).
     */
    public static EntityHash fromEntity(GtfsRealtime.FeedEntity entity) {
        var idBytes = entity.getId().getBytes(StandardCharsets.UTF_8);
        var key = ByteBuffer.allocateDirect(idBytes.length).put(idBytes).flip();

        // Wrap the hash bytes in a ByteBuffer for easy long extraction
        ByteBuffer hb = ByteBuffer.wrap(
                Hashing.murmur3_128().hashBytes(entity.toByteArray()).asBytes()
        );

        return new EntityHash(key, hb.getLong(), hb.getLong());
    }

    /**
     * Represents a GTFS entity key paired with its Murmur3_128 hash as two primitives.
     * <p/>
     * h1 = high 64 bits, h2 = low 64 bits of the 128-bit hash.
     * This avoids object overhead and allows efficient LMDB storage.
     */
    public record EntityHash(ByteBuffer key, long h1, long h2) {}
}