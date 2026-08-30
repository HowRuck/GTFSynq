package org.example.gtfsynq.shared.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.openhft.hashing.LongHashFunction;

/**
 * Hashing utilities for GTFS FeedEntity objects using XXH3.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FeedHashing {

    private static final LongHashFunction STATE_HASHER = LongHashFunction.xx3();
    private static final int DEFAULT_BUFFER_SIZE = 256;

    /**
     * Hash the raw bytes of a GTFS entity.
     *
     * @param rawBytes raw bytes of the GTFS entity
     * @return hash of the raw bytes
     */
    public static long hashBytes(byte[] rawBytes) {
        return STATE_HASHER.hashBytes(rawBytes);
    }

    /**
     * Start a reusable byte-buffer encoder for structured values.
     *
     * <p>
     * This avoids StringBuilder-based payload assembly while still producing a
     * stable
     * byte sequence for hashing.
     *
     * @return a new encoder
     */
    public static Encoder encoder() {
        return new Encoder(DEFAULT_BUFFER_SIZE);
    }

    public static final class Encoder {

        private byte[] buffer;
        private int position;

        private Encoder(int initialCapacity) {
            this.buffer = new byte[initialCapacity];
            this.position = 0;
        }

        public Encoder putLong(long value) {
            ensureCapacity(Long.BYTES + 1);
            buffer[position++] = (byte) 'L';
            putLongRaw(value);
            return this;
        }

        public Encoder putInteger(Integer value) {
            if (value == null) {
                ensureCapacity(2);
                buffer[position++] = (byte) 'N';
                buffer[position++] = (byte) '|';
            } else {
                ensureCapacity(Integer.BYTES + 2);
                buffer[position++] = (byte) 'I';
                putIntRaw(value);
            }
            return this;
        }

        public Encoder putLongObject(Long value) {
            if (value == null) {
                ensureCapacity(2);
                buffer[position++] = (byte) 'N';
                buffer[position++] = (byte) '|';
            } else {
                ensureCapacity(Long.BYTES + 1);
                buffer[position++] = (byte) 'L';
                putLongRaw(value);
            }
            return this;
        }

        public Encoder putString(String value) {
            if (value == null) {
                ensureCapacity(2);
                buffer[position++] = (byte) 'N';
                buffer[position++] = (byte) '|';
                return this;
            }

            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ensureCapacity(1 + Integer.BYTES + bytes.length + 1);
            buffer[position++] = (byte) 'S';
            putIntRaw(bytes.length);
            System.arraycopy(bytes, 0, buffer, position, bytes.length);
            position += bytes.length;
            buffer[position++] = (byte) '|';
            return this;
        }

        public Encoder putInstant(java.time.Instant value) {
            if (value == null) {
                ensureCapacity(2);
                buffer[position++] = (byte) 'N';
                buffer[position++] = (byte) '|';
                return this;
            }

            ensureCapacity(1 + Long.BYTES + Integer.BYTES + 1);
            buffer[position++] = (byte) 'T';
            putLongRaw(value.getEpochSecond());
            putIntRaw(value.getNano());
            buffer[position++] = (byte) '|';
            return this;
        }

        public long hash() {
            return STATE_HASHER.hashBytes(slice());
        }

        public byte[] bytes() {
            return slice();
        }

        private byte[] slice() {
            byte[] out = new byte[position];
            System.arraycopy(buffer, 0, out, 0, position);
            return out;
        }

        private void ensureCapacity(int additionalBytes) {
            int required = position + additionalBytes;
            if (required <= buffer.length) {
                return;
            }

            int newCapacity = buffer.length;
            while (newCapacity < required) {
                newCapacity *= 2;
            }

            byte[] resized = new byte[newCapacity];
            System.arraycopy(buffer, 0, resized, 0, position);
            buffer = resized;
        }

        private void putLongRaw(long value) {
            ensureCapacity(Long.BYTES);
            ByteBuffer.wrap(buffer, position, Long.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(value);
            position += Long.BYTES;
        }

        private void putIntRaw(int value) {
            ensureCapacity(Integer.BYTES);
            ByteBuffer.wrap(buffer, position, Integer.BYTES)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(value);
            position += Integer.BYTES;
        }
    }
}
