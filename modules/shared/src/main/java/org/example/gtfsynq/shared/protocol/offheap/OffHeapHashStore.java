package org.example.gtfsynq.shared.protocol.offheap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;

@Component
@Slf4j
@RequiredArgsConstructor
public class OffHeapHashStore implements AutoCloseable {

    private final OffHeapLongTable binTable;

    private final StampedLock lock = new StampedLock();

    public static final long PROHIBITED_WRITE = -1L;
    protected static final long[] PROHIBITED_WRITE_ARRAY = new long[] {
        PROHIBITED_WRITE, PROHIBITED_WRITE, PROHIBITED_WRITE,
    };

    private static final int TTL_MINUTES = 60;

    /**
     * Percentage of occupied slots at which an auto-resize is attempted.
     */
    private static final int RESIZE_TRIGGER_PERCENT = 70;

    /**
     * A full maintenance scan is only worth it once at least this fraction of
     * capacity (1 in 2^shift) has been recycled by overwriting expired slots.
     * That signals the table is cluttered with dead entries worth compacting
     * or shrinking. Below the threshold {@link #autoTune()} stays cheap.
     */
    private static final int STALE_OVERWRITE_SHIFT = 3;

    public volatile int currentMinute = (int) (System.currentTimeMillis() / 60000);

    /**
     * Number of occupied (non-empty) slots, including expired ones. Guarded by
     * the write lock.
     */
    private volatile long size;

    /**
     * Number of inserts that had to overwrite an expired slot. Signals that
     * the table is cluttered with dead entries worth compacting. Guarded by
     * the write lock.
     */
    private long staleOverwrites;

    private boolean resizeSaturated;

    private final AtomicBoolean metricsBound = new AtomicBoolean(false);

    /**
     * Binds Micrometer gauges lazily so plain unit-test construction
     * ({@code new OffHeapHashStore(table)}) keeps working without a registry.
     */
    @Autowired(required = false)
    public void bindMetrics(MeterRegistry registry) {
        if (registry == null || !metricsBound.compareAndSet(false, true)) {
            return;
        }
        Gauge.builder("offheap.store.size", this, OffHeapHashStore::size)
                .description("Occupied off-heap hash store slots, including expired ones")
                .register(registry);
        Gauge.builder("offheap.store.capacity", binTable, OffHeapLongTable::capacity)
                .description("Total off-heap hash store slots")
                .register(registry);
        Gauge.builder("offheap.store.load.ratio", this, store -> {
                    var capacity = binTable.capacity();
                    return capacity == 0 ? 0.0 : (double) store.size() / capacity;
                })
                .description("Occupied / capacity ratio of the off-heap hash store")
                .register(registry);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        size = binTable.countOccupied();
        log.info("OffHeapHashStore initialized with {} occupied slots (capacity {})", size, binTable.capacity());
    }

    public long get(long key) {
        return getAndTryOptimistic(key)[0];
    }

    public long[] getWithCustomSlots(long key) {
        return getAndTryOptimistic(key);
    }

    public long[] getAndTryOptimistic(long key) {
        var optimisticLockStamp = lock.tryOptimisticRead();

        var data = getWithLock(key, optimisticLockStamp);
        if (data[0] != PROHIBITED_WRITE) {
            return data;
        }

        var fullLockStamp = lock.readLock();
        try {
            return getWithLock(key, fullLockStamp);
        } finally {
            lock.unlockRead(fullLockStamp);
        }
    }

    long[] getWithLock(long key, long stamp) {
        var capacity = binTable.capacity();
        var mask = binTable.capacityMask();
        var index = OffHeapLongTable.hash(key) & mask;

        for (var i = 0L; i < capacity; i++) {
            var slotKey = binTable.getKey(index);

            if (slotKey == OffHeapLongTable.EMPTY_VALUE) {
                return missWithValidation(stamp);
            }

            if (slotKey == key) {
                var slotExpiry = binTable.getExpiry(index);
                if (slotExpiry <= currentMinute) {
                    return missWithValidation(stamp);
                }

                var data = new long[] {
                    binTable.getValue(index), binTable.getCustomSlot1(index), binTable.getCustomSlot2(index),
                };

                // Validate optimistic read lock
                if (!lock.validate(stamp)) {
                    return PROHIBITED_WRITE_ARRAY;
                }

                return data;
            }

            // Move to the next slot in the chain
            index = (index + 1) & mask;
        }

        return missWithValidation(stamp);
    }

    private long[] missWithValidation(long stamp) {
        return lock.validate(stamp) ? OffHeapLongTable.EMPTY_VALUE_ARRAY : PROHIBITED_WRITE_ARRAY;
    }

    public void put(long key, long value) {
        if (key == OffHeapLongTable.EMPTY_VALUE) {
            throw new IllegalArgumentException("Key cannot be empty");
        }

        var stamp = lock.writeLock();
        try {
            insert(key, value, currentMinute + TTL_MINUTES, 0, 0);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public void put(long key, long value, int customSlot1, int customSlot2) {
        if (key == OffHeapLongTable.EMPTY_VALUE) {
            throw new IllegalArgumentException("Key cannot be empty");
        }

        var stamp = lock.writeLock();
        try {
            insert(key, value, currentMinute + TTL_MINUTES, customSlot1, customSlot2);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    void insert(long key, long value, int expiry, int customSlot1, int customSlot2) {
        var capacity = binTable.capacity();
        var mask = binTable.capacityMask();
        var index = OffHeapLongTable.hash(key) & mask;
        long firstAvailableIndex = -1;

        for (var i = 0L; i < capacity; i++) {
            var slotKey = binTable.getKey(index);

            // If we hit an empty slot, the key is definitely not in the map.
            if (slotKey == OffHeapLongTable.EMPTY_VALUE) {
                // If we saw an expired slot earlier, overwrite it to keep elements tightly
                // packed.
                // Otherwise, write to this empty slot.
                if (firstAvailableIndex != -1) {
                    writeSlot(firstAvailableIndex, key, value, expiry, customSlot1, customSlot2);
                    staleOverwrites++;
                } else {
                    writeSlot(index, key, value, expiry, customSlot1, customSlot2);
                    size++;
                    maybeGrow();
                }
                return;
            }

            // If we find the exact key, update it in place.
            if (slotKey == key) {
                writeSlot(index, key, value, expiry, customSlot1, customSlot2);
                return;
            }

            // If we pass an expired slot, remember it so we can overwrite it later.
            // We MUST NOT return yet, because the exact key might be further down the
            // chain!
            var slotExpiry = binTable.getExpiry(index);
            if (slotExpiry <= currentMinute && firstAvailableIndex == -1) {
                firstAvailableIndex = index;
            }

            // Move to the next slot in the chain
            index = (index + 1) & mask;
        }

        // If we loop through the whole table and it's full:
        if (firstAvailableIndex != -1) {
            // We can evict the expired element we found
            writeSlot(firstAvailableIndex, key, value, expiry, customSlot1, customSlot2);
            staleOverwrites++;
        } else {
            throw new IllegalStateException("OffHeapHashStore is completely full!");
        }
    }

    /**
     * Asks the off-heap table to grow/compact if the occupancy exceeds the
     * resize trigger. Must be called under the write lock.
     */
    private void maybeGrow() {
        var capacity = binTable.capacity();

        if (size * 100 < capacity * RESIZE_TRIGGER_PERCENT) {
            resizeSaturated = false;
            return;
        }

        if (capacity >= OffHeapLongTable.MAX_CAPACITY && resizeSaturated) {
            // Table cannot grow any further; avoid rescanning on every insert.
            return;
        }

        size = binTable.autoResize(currentMinute);
        staleOverwrites = 0;
        resizeSaturated = size * 100 >= binTable.capacity() * RESIZE_TRIGGER_PERCENT;
    }

    /**
     * Periodic maintenance pass that rebalances the table based on the *live*
     * entry count, so shrink and expired-clutter compaction happen even when
     * raw occupancy is below the grow trigger (expired entries are only
     * lazily overwritten, so occupancy itself never drops on its own).
     *
     * <p>
     * The O(capacity) scan runs under the write lock, so it stalls readers
     * and writers for its duration. It is therefore skipped unless enough
     * expired-slot overwrites have accumulated to justify the pause.
     */
    @Scheduled(fixedRate = 60_000)
    public void autoTune() {
        var stamp = lock.writeLock();
        try {
            var capacity = binTable.capacity();

            // Occupied slots bound the live count from above, so occupancy
            // below the shrink watermark guarantees a shrink will apply —
            // no expired-slot overwrite pressure is needed to detect it.
            var shrinkable = size * 100 <= capacity * OffHeapLongTable.SHRINK_LOW_WATERMARK_PERCENT;

            if (!shrinkable && staleOverwrites < (capacity >>> STALE_OVERWRITE_SHIFT)) {
                return;
            }

            size = binTable.autoResize(currentMinute);
            staleOverwrites = 0;
            resizeSaturated = binTable.capacity() >= OffHeapLongTable.MAX_CAPACITY
                    && size * 100 >= binTable.capacity() * RESIZE_TRIGGER_PERCENT;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void writeSlot(long index, long k, long v, int e, int c1, int c2) {
        binTable.setKey(index, k);
        binTable.setValue(index, v);
        binTable.setExpiry(index, e);
        binTable.setPsl(index, 0);
        binTable.setCustomSlot1(index, c1);
        binTable.setCustomSlot2(index, c2);
    }

    public long size() {
        return size;
    }

    @Override
    public void close() {
        binTable.close();
    }

    @Scheduled(fixedRate = 60000)
    public void tickMinute() {
        currentMinute = (int) (System.currentTimeMillis() / 60000);
    }

    @Scheduled(fixedRate = 60000)
    public void printLoadPercentage() {
        var occupied = size;
        var capacity = binTable.capacity();
        var loadPercentage = ((double) occupied / capacity) * 100;
        log.info(String.format("[OffHeapHashStore] Load: %.2f%% (%d/%d)", loadPercentage, occupied, capacity));
    }
}
