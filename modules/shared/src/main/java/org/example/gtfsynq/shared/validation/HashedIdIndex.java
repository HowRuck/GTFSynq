package org.example.gtfsynq.shared.validation;

import java.util.concurrent.ThreadLocalRandom;
import net.openhft.hashing.LongHashFunction;

/**
 * Membership index for GTFS ids that stores 64-bit hashes instead of the ids themselves.
 *
 * <p>Referential and duplicate checks only ever ask "was this id seen", so retaining the strings is
 * pure overhead: a {@code HashSet<String>} costs tens of bytes per id plus the string itself, while
 * this costs 8 bytes per slot at 50% load. The winning property for a streaming validator is that
 * ids from parsed rows stay collectable instead of being pinned for the whole archive.
 *
 * <p>The hash is XXH3 with a random per-JVM seed. A fixed seed would let a hostile archive
 * deliberately construct an id colliding with another, which for duplicate detection would mean a
 * false violation and for a foreign key a silently missed one; an unpredictable seed removes the
 * offline precomputation. Accidental collisions remain bounded by the birthday bound: at 2 million
 * ids the probability of any collision is under 10^-7.
 *
 * <p>Only 64 bits are kept, so membership is probabilistic, not exact. Callers must treat {@link
 * #contains} as "probably present"; a false negative in one direction hides one violation, and
 * {@link #truncated()} tells them when tracking stopped so they can avoid reporting the opposite.
 */
final class HashedIdIndex {

    /** Default cap on distinct ids tracked in one index (8M ids, ~128 MiB of slots). */
    static final int DEFAULT_MAX_TRACKED_IDS = 8_000_000;

    /** Slot marker for "never written"; a real hash of 0 is remapped away by {@link #spread}. */
    private static final long EMPTY = 0L;

    /** Keeps zero out of the table without biasing the distribution. */
    private static final long ZERO_REPLACEMENT = 0x9E3779B97F4A7C15L;

    private static final double LOAD_FACTOR = 0.5;

    private static final LongHashFunction HASH =
            LongHashFunction.xx3(ThreadLocalRandom.current().nextLong());

    private final int maxEntries;

    private long[] slots;

    private int mask;

    private int size;

    private boolean truncated;

    /** Slots the table starts with; it grows on demand up to the entry limit. */
    private static final int INITIAL_SLOTS = 1 << 10;

    HashedIdIndex(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        this.slots = new long[INITIAL_SLOTS];
        this.mask = slots.length - 1;
    }

    /** Outcome of {@link #add}, distinguishing "seen before" from "not tracked". */
    enum IdSlot {
        ADDED,
        PRESENT,
        UNTRACKED
    }

    /** Whether at least one id could not be tracked because the entry limit was reached. */
    boolean truncated() {
        return truncated;
    }

    /** Number of tracked ids. */
    int size() {
        return size;
    }

    IdSlot add(String id) {
        long hash = spread(id);
        int at = index(hash);
        while (slots[at] != EMPTY) {
            if (slots[at] == hash) {
                return IdSlot.PRESENT;
            }
            at = (at + 1) & mask;
        }
        if (size >= maxEntries) {
            truncated = true;
            return IdSlot.UNTRACKED;
        }
        slots[at] = hash;
        size++;
        // The load factor stays at or below 0.5, so a probe always meets an empty slot and the
        // table can never fill completely.
        if (size > slots.length * LOAD_FACTOR) {
            grow();
        }
        return IdSlot.ADDED;
    }

    boolean contains(String id) {
        long hash = spread(id);
        int at = index(hash);
        while (slots[at] != EMPTY) {
            if (slots[at] == hash) {
                return true;
            }
            at = (at + 1) & mask;
        }
        return false;
    }

    private int index(long hash) {
        return (int) hash & mask;
    }

    private void grow() {
        var previous = slots;
        slots = new long[previous.length * 2];
        mask = slots.length - 1;
        for (long hash : previous) {
            if (hash != EMPTY) {
                int at = index(hash);
                while (slots[at] != EMPTY) {
                    at = (at + 1) & mask;
                }
                slots[at] = hash;
            }
        }
    }

    private static long spread(String id) {
        long hash = HASH.hashChars(id);
        return hash == EMPTY ? ZERO_REPLACEMENT : hash;
    }
}
