package org.example.gtfsynq.shared.protocol.offheap;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.persistence.OffHeapFileScribe;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Shared off-heap memory helper for a packed {@code long -> long -> long} table
 * row layout with automatic resizing.
 *
 * <p>
 * This class centralizes the low-level foreign-memory setup so the hash store
 * can focus on
 * behavior rather than repeated layout and access boilerplate.
 *
 * <p>
 * The table starts at {@link #INITIAL_CAPACITY} slots and grows (doubling) or
 * shrinks (halving) as the live load changes. All resize operations must be
 * invoked while the owning store holds its write lock. Old memory segments are
 * kept alive for a grace period after a resize so concurrent optimistic
 * readers never touch freed memory.
 */
@Slf4j
public final class OffHeapLongTable implements AutoCloseable {

    /**
     * Number of slots the table starts with (2^16, 2 MiB)
     */
    public static final long INITIAL_CAPACITY = 65_536L;

    /**
     * Upper bound for the number of slots (2^26, ~2 GiB)
     */
    public static final long MAX_CAPACITY = 1L << 26;

    /**
     * Grow (double) when the live load reaches this percentage
     */
    private static final int GROW_HIGH_WATERMARK_PERCENT = 70;

    /**
     * Shrink (halve) when the live load drops to this percentage. Also public
     * because the store gates its maintenance scans on occupancy falling below
     * this watermark (occupancy is an upper bound on the live load, so below
     * this a shrink is guaranteed to apply).
     */
    public static final int SHRINK_LOW_WATERMARK_PERCENT = 17;

    /**
     * Compact at the same capacity when the live load drops below this
     * percentage (i.e. the table is cluttered with expired entries)
     */
    private static final int COMPACT_LOAD_PERCENT = 50;

    /**
     * How long retired arenas are kept alive after a resize so in-flight
     * optimistic readers can finish safely
     */
    private static final long ARENA_RETENTION_NANOS = 60 * 1_000_000_000L;

    private static final int KEY_OFFSET = 0;
    private static final int VALUE_OFFSET = KEY_OFFSET + Long.BYTES;
    private static final int EXPIRY_OFFSET = VALUE_OFFSET + Long.BYTES;
    private static final int PSL_OFFSET = EXPIRY_OFFSET + Integer.BYTES;
    private static final int CUSTOM_SLOT1_OFFSET = PSL_OFFSET + Integer.BYTES;
    private static final int CUSTOM_SLOT2_OFFSET = CUSTOM_SLOT1_OFFSET + Integer.BYTES;

    /**
     * Row size in bytes. Derived from the field offsets above so it cannot
     * drift.
     *
     * <p>
     * Layout:
     * <ul>
     * <li>key: 8 bytes</li>
     * <li>value: 8 bytes</li>
     * <li>expiry: 4 bytes</li>
     * <li>psl: 4 bytes</li>
     * <li>customSlot1: 4 bytes</li>
     * <li>customSlot2: 4 bytes</li>
     * </ul>
     */
    public static final int SLOT_SIZE = CUSTOM_SLOT2_OFFSET + Integer.BYTES;

    private static final long SEGMENT_ALIGNMENT = 64;

    public static final long EMPTY_VALUE = 0L;
    public static final long[] EMPTY_VALUE_ARRAY = new long[] {
        EMPTY_VALUE, EMPTY_VALUE, EMPTY_VALUE,
    };

    private final OffHeapFileScribe scribe;

    record TableView(Arena arena, MemorySegment segment, long capacity, long capacityMask) {}

    private volatile TableView view;

    /**
     * Guards {@link #view} publication, {@link #closed}, and
     * {@link #retiredArenas}. Reads of {@link #view} remain lock-free.
     */
    private volatile boolean closed;
    private boolean initializationComplete;

    private final Deque<RetiredArena> retiredArenas = new ArrayDeque<>();
    private final Object lifecycleLock = new Object();
    private final Object backupLock = new Object();

    private record RetiredArena(Arena arena, long byteSize, long retiredAtNanos) {}

    public OffHeapLongTable(OffHeapFileScribe scribe) {
        this.scribe = scribe;
        this.view = allocate(INITIAL_CAPACITY);

        try {
            scribe.load(this);
        } finally {
            synchronized (lifecycleLock) {
                initializationComplete = true;
            }
        }
    }

    private static TableView allocate(long capacity) {
        var arena = Arena.ofShared();
        try {
            return new TableView(
                    arena, arena.allocate(capacity * SLOT_SIZE, SEGMENT_ALIGNMENT), capacity, capacity - 1);
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    TableView snapshot() {
        if (closed) throw new IllegalStateException("OffHeap table is closed");
        return view;
    }

    public MemorySegment getSegment() {
        if (closed) throw new IllegalStateException("OffHeap table is closed");
        return view.segment();
    }

    public long capacity() {
        return view.capacity();
    }

    long getKey(MemorySegment source, long index) {
        return source.get(ValueLayout.JAVA_LONG, slotOffset(index) + KEY_OFFSET);
    }

    long getValue(MemorySegment source, long index) {
        return source.get(ValueLayout.JAVA_LONG, slotOffset(index) + VALUE_OFFSET);
    }

    int getExpiry(MemorySegment source, long index) {
        return source.get(ValueLayout.JAVA_INT, slotOffset(index) + EXPIRY_OFFSET);
    }

    int getPsl(MemorySegment source, long index) {
        return source.get(ValueLayout.JAVA_INT, slotOffset(index) + PSL_OFFSET);
    }

    int getCustomSlot1(MemorySegment source, long index) {
        return source.get(ValueLayout.JAVA_INT, slotOffset(index) + CUSTOM_SLOT1_OFFSET);
    }

    int getCustomSlot2(MemorySegment source, long index) {
        return source.get(ValueLayout.JAVA_INT, slotOffset(index) + CUSTOM_SLOT2_OFFSET);
    }

    public static long hash(long key) {
        key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
        key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
        return key ^ (key >>> 31);
    }

    private static long slotOffset(long index) {
        return index * SLOT_SIZE;
    }

    static void putSlot(
            MemorySegment seg,
            long index,
            long key,
            long value,
            int expiry,
            int psl,
            int customSlot1,
            int customSlot2) {
        long offset = slotOffset(index);
        seg.set(ValueLayout.JAVA_LONG, offset + KEY_OFFSET, key);
        seg.set(ValueLayout.JAVA_LONG, offset + VALUE_OFFSET, value);
        seg.set(ValueLayout.JAVA_INT, offset + EXPIRY_OFFSET, expiry);
        seg.set(ValueLayout.JAVA_INT, offset + PSL_OFFSET, psl);
        seg.set(ValueLayout.JAVA_INT, offset + CUSTOM_SLOT1_OFFSET, customSlot1);
        seg.set(ValueLayout.JAVA_INT, offset + CUSTOM_SLOT2_OFFSET, customSlot2);
    }

    /**
     * Counts the number of live (non-empty and non-expired) slots.
     *
     * @param currentMinute the current minute epoch used for expiry checks
     * @return the number of live entries
     */
    private long countLiveEntries(int currentMinute) {
        var current = view;
        var cap = current.capacity();
        var seg = current.segment();
        var live = 0L;
        for (var i = 0L; i < cap; i++) {
            if (getKey(seg, i) != EMPTY_VALUE && getExpiry(seg, i) > currentMinute) {
                live++;
            }
        }
        return live;
    }

    /**
     * Counts the number of occupied (non-empty) slots, including expired ones.
     *
     * @return the number of occupied slots
     */
    long countOccupied() {
        var current = view;
        var cap = current.capacity();
        var seg = current.segment();
        var occupied = 0L;
        for (var i = 0L; i < cap; i++) {
            if (getKey(seg, i) != EMPTY_VALUE) {
                occupied++;
            }
        }
        return occupied;
    }

    /**
     * Grows, shrinks or compacts the table depending on the current live load.
     * Must only be called while the owning store holds its write lock.
     *
     * @param currentMinute the current minute epoch used for expiry checks
     * @return the number of live entries after the operation
     */
    long autoResize(int currentMinute) {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("OffHeap table is closed");

            var cap = view.capacity();
            var live = countLiveEntries(currentMinute);

            if (live * 100 >= cap * GROW_HIGH_WATERMARK_PERCENT) {
                if (cap < MAX_CAPACITY) {
                    return rehash(Math.min(cap * 2, MAX_CAPACITY), currentMinute);
                }
                log.warn("OffHeap table reached max capacity of {} slots ({} live entries)", cap, live);
            } else if (live * 100 <= cap * SHRINK_LOW_WATERMARK_PERCENT && cap > INITIAL_CAPACITY) {
                return rehash(Math.max(cap / 2, INITIAL_CAPACITY), currentMinute);
            } else if (live * 100 < cap * COMPACT_LOAD_PERCENT) {
                // The table is cluttered with expired entries: compact it in place.
                return rehash(cap, currentMinute);
            }

            return live;
        }
    }

    /**
     * Rebuilds the table at the given capacity, dropping expired entries.
     * Must only be called while the owning store holds its write lock.
     *
     * @param newCapacity   the new capacity, a power of two
     * @param currentMinute the current minute epoch used for expiry checks
     * @return the number of live entries that were rehashed
     */
    private long rehash(long newCapacity, int currentMinute) {
        if (newCapacity < 1 || Long.bitCount(newCapacity) != 1 || newCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("New capacity must be a power of two in [1, " + MAX_CAPACITY + "]");
        }

        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("OffHeap table is closed");

            var current = view;
            var oldCap = current.capacity();
            var oldSegment = current.segment();
            var newMask = newCapacity - 1;
            var newView = allocate(newCapacity);
            var newSegment = newView.segment();
            var live = 0L;
            var published = false;

            try {
                for (var i = 0L; i < oldCap; i++) {
                    var key = getKey(oldSegment, i);
                    if (key == EMPTY_VALUE) continue;
                    if (getExpiry(oldSegment, i) <= currentMinute) continue;

                    var home = hash(key) & newMask;
                    var index = home;
                    while (getKey(newSegment, index) != EMPTY_VALUE) {
                        index = (index + 1) & newMask;
                        if (index == home) {
                            // Should be impossible: caller guarantees live <
                            // newCapacity via the autoResize watermarks.
                            throw new IllegalStateException("Rehash probe exhausted at capacity " + newCapacity);
                        }
                    }

                    putSlot(
                            newSegment,
                            index,
                            key,
                            getValue(oldSegment, i),
                            getExpiry(oldSegment, i),
                            getPsl(oldSegment, i),
                            getCustomSlot1(oldSegment, i),
                            getCustomSlot2(oldSegment, i));
                    live++;
                }

                swapSegment(newView);
                published = true;
            } finally {
                if (!published) newView.arena().close();
            }

            log.info(
                    "OffHeap table resized from {} to {} slots ({} live entries, {} bytes)",
                    oldCap,
                    newCapacity,
                    live,
                    newSegment.byteSize());

            return live;
        }
    }

    /**
     * Replaces the backing memory segment without rehashing, e.g. when a
     * restored snapshot has a different capacity than the current table. Only
     * safe to call before the table is exposed to traffic
     *
     * @param newCapacity the capacity to allocate, a power of two
     */
    public void reallocate(long newCapacity) {
        if (newCapacity < 1 || Long.bitCount(newCapacity) != 1 || newCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("New capacity must be a power of two in [1, " + MAX_CAPACITY + "]");
        }
        synchronized (lifecycleLock) {
            if (initializationComplete) {
                throw new IllegalStateException("OffHeap table can only be reallocated during initialization");
            }
        }

        var newView = allocate(newCapacity);
        var published = false;
        try {
            swapSegment(newView);
            published = true;
        } finally {
            if (!published) newView.arena().close();
        }

        log.info(
                "OffHeap table reallocated to {} slots ({} bytes) for snapshot restore",
                newCapacity,
                newView.segment().byteSize());
    }

    private void swapSegment(TableView newView) {
        synchronized (lifecycleLock) {
            if (closed) throw new IllegalStateException("OffHeap table is closed");

            var oldView = this.view;
            var retired = new RetiredArena(
                    oldView.arena(), oldView.segment().byteSize(), System.nanoTime());
            retiredArenas.addLast(retired);
            this.view = newView;
        }
    }

    /**
     * Closes arenas that were retired by a resize once the retention grace
     * period has elapsed
     */
    @Scheduled(fixedRate = 60_000)
    public void purgeRetiredArenas() {
        synchronized (backupLock) {
            var deadline = System.nanoTime() - ARENA_RETENTION_NANOS;
            while (true) {
                RetiredArena retired;
                synchronized (lifecycleLock) {
                    retired = retiredArenas.peekFirst();
                    if (retired == null || retired.retiredAtNanos() > deadline) {
                        return;
                    }
                    retiredArenas.pollFirst();
                }
                try {
                    retired.arena().close();
                } catch (Throwable t) {
                    log.error("Failed to close retired off-heap arena", t);
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (backupLock) {
            synchronized (lifecycleLock) {
                if (closed) return;
                closed = true;

                try {
                    view.arena().close();
                } finally {
                    for (var retired : retiredArenas) {
                        try {
                            retired.arena().close();
                        } catch (Throwable t) {
                            log.error("Failed to close retired off-heap arena", t);
                        }
                    }
                    retiredArenas.clear();
                }
            }
        }
    }

    /**
     * Total native bytes currently reserved: live segment plus retired arenas
     * still within the reader grace period.
     */
    public long nativeBytes() {
        synchronized (lifecycleLock) {
            return view.segment().byteSize() + retiredBytesLocked();
        }
    }

    public long retiredBytes() {
        synchronized (lifecycleLock) {
            return retiredBytesLocked();
        }
    }

    private long retiredBytesLocked() {
        var total = 0L;
        for (var retired : retiredArenas) {
            total += retired.byteSize();
        }
        return total;
    }

    @Scheduled(fixedRate = 60_000)
    public void backup() {
        synchronized (backupLock) {
            if (closed) return;
            scribe.dump(this);
        }
    }
}
