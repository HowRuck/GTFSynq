package org.example.sirianalyzer.util;

import com.google.common.hash.Hashing;
import com.google.transit.realtime.GtfsRealtime;
import lombok.NoArgsConstructor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Hashing utilities for GTFS FeedEntity objects using Murmur3 128-bit.
 */
@NoArgsConstructor
public final class FeedEntityHashing {

    // Murmur3 uses little-endian byte order
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Produces an {@link EntityHash} for a single {@link GtfsRealtime.FeedEntity}.
     * The key is the entity's UTF-8 ID bytes; the hash is stored as two longs (h1, h2).
     */
    public static EntityHash fromEntity(GtfsRealtime.FeedEntity entity) {
        var idBytes = entity.getId().getBytes(StandardCharsets.UTF_8);
        var key = ByteBuffer.allocateDirect(idBytes.length).put(idBytes).flip();

        var hashBytes = Hashing.murmur3_128()
                .hashBytes(entity.toByteArray())
                .asBytes();

        // Extract longs using VarHandle to skip object overhead
        // Offset 0 is h1, Offset 8 is h2
        var h1 = (long) LONG_VIEW.get(hashBytes, 0);
        var h2 = (long) LONG_VIEW.get(hashBytes, 8);

        return new EntityHash(key, h1, h2);
    }

    /**
     * Represents a GTFS entity key paired with its Murmur3_128 hash as two primitives.
     * <p/>
     * h1 = high 64 bits, h2 = low 64 bits of the 128-bit hash.
     * This avoids object overhead and allows efficient LMDB storage.
     */
    public record EntityHash(ByteBuffer key, long h1, long h2) {
    }
}