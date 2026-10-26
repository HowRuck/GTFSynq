package org.example.gtfsynq.ui.diagnostics;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionLockAcquiredEvent;
import com.vaadin.flow.server.SessionLockReleasedEvent;
import com.vaadin.flow.server.SessionLockRequestedEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reports Vaadin session-lock contention, and names the thread holding a lock that is stuck.
 *
 * <p>Every request and every {@code ui.access()} runs under the session lock, so a holder that
 * never releases it freezes that browser session for good: new requests and push connections wait
 * without a timeout. Vaadin logs the threads that are waiting (the Vert.x blocked-thread checker),
 * but never the holder, which is the one piece of information needed to find the cause.
 *
 * <p>This listener records the holder and its stack the moment a lock is acquired, warns when
 * either the wait or the hold passes {@code warn-after}, and dumps the holder once a hold passes
 * {@code dump-after}. The dump only fires while a lock is still held, so it stays silent in normal
 * operation.
 */
@ApplicationScoped
@Slf4j
public class SessionLockDiagnostics implements VaadinServiceInitListener {

    private static final int STACK_FRAMES = 25;

    private final SessionLockMonitor monitor = new SessionLockMonitor();

    private final long warnAfterMillis;

    private final long dumpAfterMillis;

    private final AtomicBoolean dumped = new AtomicBoolean();

    public SessionLockDiagnostics(
            @ConfigProperty(name = "gtfsynq.ui.session-lock.warn-after", defaultValue = "10s") Duration warnAfter,
            @ConfigProperty(name = "gtfsynq.ui.session-lock.dump-after", defaultValue = "30s") Duration dumpAfter) {
        this.warnAfterMillis = warnAfter.toMillis();
        this.dumpAfterMillis = dumpAfter.toMillis();
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        var bus = event.getSource().getEventBus();

        bus.addListener(SessionLockRequestedEvent.class, lockEvent -> monitor.requested());

        bus.addListener(SessionLockAcquiredEvent.class, lockEvent -> {
            var hold = monitor.acquired();
            if (hold.waited().toMillis() >= warnAfterMillis) {
                log.warn(
                        "Vaadin session lock waited {} ms on thread '{}', acquired at:\n{}",
                        hold.waited().toMillis(),
                        hold.thread().getName(),
                        hold.formattedAcquisitionStack());
            }
        });

        bus.addListener(SessionLockReleasedEvent.class, lockEvent -> {
            var hold = monitor.released();
            if (hold == null) {
                return;
            }
            var heldMillis = hold.heldFor(Instant.now()).toMillis();
            if (heldMillis >= warnAfterMillis) {
                log.warn(
                        "Vaadin session lock held for {} ms by thread '{}', acquired at:\n{}",
                        heldMillis,
                        hold.thread().getName(),
                        hold.formattedAcquisitionStack());
            }
        });
    }

    /**
     * Dumps a lock that has been held far past any reasonable request time.
     *
     * <p>This is the only way to see the holder of a wedged lock: the release event never fires, so
     * the acquisition stack recorded by the listener is what identifies the code path that took the
     * lock and never gave it back.
     */
    @Scheduled(every = "15s")
    void reportWedgedLock() {
        var wedged = monitor.heldLongerThan(Duration.ofMillis(dumpAfterMillis));
        if (wedged.isEmpty()) {
            dumped.set(false);
            return;
        }
        if (!dumped.compareAndSet(false, true)) {
            return;
        }
        for (var hold : wedged) {
            log.error(
                    "Vaadin session lock still held by thread '{}' for {} ms (waited {} ms to acquire it).\n"
                            + "Acquired at:\n{}\nCurrent stack:\n{}",
                    hold.thread().getName(),
                    hold.heldFor(Instant.now()).toMillis(),
                    hold.waited().toMillis(),
                    hold.formattedAcquisitionStack(),
                    currentStack(hold.thread()));
        }
    }

    private static String currentStack(Thread thread) {
        try {
            var frames = thread.getStackTrace();
            var stack = new StringBuilder();
            for (var frame : frames) {
                if (stack.length() >= STACK_FRAMES * 60) {
                    stack.append("\t…\n");
                    break;
                }
                stack.append("\tat ").append(frame).append('\n');
            }
            return stack.toString();
        } catch (RuntimeException e) {
            return "\t(unavailable: " + e.getMessage() + ")";
        }
    }
}
