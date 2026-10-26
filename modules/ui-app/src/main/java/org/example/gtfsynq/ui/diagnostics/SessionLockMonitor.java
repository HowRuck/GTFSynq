package org.example.gtfsynq.ui.diagnostics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks who holds a Vaadin session lock and for how long.
 *
 * <p>Vaadin serialises all server-side work for a session behind a single lock, and it does not
 * time out a lock wait. When the holder never releases it — the classic case is a background
 * {@code ui.access()} task whose push cannot be written because the event loop is itself waiting
 * for the lock — the whole session freezes and every reconnect queues behind it. The only way to
 * find the culprit is to record the thread and stack at acquisition, before the deadlock hides it.
 *
 * <p>Keyed by the acquiring thread, because the events report the outermost acquisition per lock and
 * a thread runs one request at a time. A thread holding two sessions' locks at once would be
 * recorded once; that cannot happen through normal request handling, and this is a diagnostic.
 */
public final class SessionLockMonitor {

    /**
     * One outermost lock hold.
     *
     * @param thread the thread holding the lock
     * @param acquiredAt when it acquired the lock
     * @param waited how long it waited to acquire it
     * @param acquisitionStack where the thread acquired it, i.e. what it is doing
     */
    public record Hold(Thread thread, Instant acquiredAt, Duration waited, List<StackTraceElement> acquisitionStack) {

        /** How long this hold has lasted by the given moment. */
        public Duration heldFor(Instant now) {
            return Duration.between(acquiredAt, now);
        }

        /** The acquisition stack, formatted one frame per line for a log message. */
        public String formattedAcquisitionStack() {
            return acquisitionStack.stream().map(frame -> "\tat " + frame).collect(Collectors.joining("\n"));
        }
    }

    private final Map<Thread, Hold> held = new ConcurrentHashMap<>();

    private final ThreadLocal<Long> requestedAt = new ThreadLocal<>();

    /** Records that the current thread is about to wait for a lock. */
    public void requested() {
        requestedAt.set(System.nanoTime());
    }

    /**
     * Records that the current thread acquired a lock.
     *
     * @return the hold, including where it was acquired and how long the wait was
     */
    public Hold acquired() {
        var now = System.nanoTime();
        var requested = requestedAt.get();
        requestedAt.remove();
        var waited = Duration.ofNanos(requested == null ? 0 : now - requested);
        var thread = Thread.currentThread();
        var hold = new Hold(thread, Instant.now(), waited, List.of(thread.getStackTrace()));
        held.put(thread, hold);
        return hold;
    }

    /**
     * Records that the current thread released its lock.
     *
     * @return the hold that ended, or {@code null} if it was never recorded
     */
    public Hold released() {
        requestedAt.remove();
        return held.remove(Thread.currentThread());
    }

    /**
     * Holds that are still open and have lasted at least the given time.
     *
     * @param threshold the age at which a hold is considered stuck
     * @return the stuck holds, oldest first
     */
    public List<Hold> heldLongerThan(Duration threshold) {
        var now = Instant.now();
        return held.values().stream()
                .filter(hold -> hold.heldFor(now).compareTo(threshold) >= 0)
                .sorted((left, right) -> left.acquiredAt().compareTo(right.acquiredAt()))
                .toList();
    }

    /** Number of locks currently held, for tests and diagnostics. */
    int openHolds() {
        return held.size();
    }
}
