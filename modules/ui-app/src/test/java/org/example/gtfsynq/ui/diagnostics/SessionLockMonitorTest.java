package org.example.gtfsynq.ui.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionLockMonitorTest {

    private final SessionLockMonitor monitor = new SessionLockMonitor();

    @AfterEach
    void clearHolds() {
        monitor.released();
    }

    @Test
    void aHeldLockIsReportedUntilItIsReleased() {
        monitor.requested();
        var hold = monitor.acquired();

        assertEquals(Thread.currentThread(), hold.thread(), "the acquiring thread identifies the holder");
        assertEquals(1, monitor.heldLongerThan(Duration.ZERO).size());

        assertEquals(hold, monitor.released());
        assertTrue(monitor.heldLongerThan(Duration.ZERO).isEmpty(), "a released lock is no longer held");
    }

    @Test
    void theWaitIsMeasuredFromTheRequest() throws InterruptedException {
        monitor.requested();
        Thread.sleep(20);
        var hold = monitor.acquired();

        assertTrue(
                hold.waited().toMillis() >= 15,
                "the wait must cover the time between the request and the acquisition, was " + hold.waited());
    }

    @Test
    void aHoldShorterThanTheThresholdIsNotReported() {
        monitor.acquired();

        assertTrue(monitor.heldLongerThan(Duration.ofMinutes(1)).isEmpty(), "a fresh hold is not stuck yet");
        assertEquals(1, monitor.heldLongerThan(Duration.ZERO).size());
    }

    @Test
    void theAcquisitionStackNamesTheCodeThatTookTheLock() {
        monitor.acquired();

        var hold = monitor.heldLongerThan(Duration.ZERO).getFirst();

        assertTrue(
                hold.formattedAcquisitionStack().contains("SessionLockMonitorTest"),
                "the stack must point at the caller, was:\n" + hold.formattedAcquisitionStack());
    }

    @Test
    void releasingWithoutAcquiringIsHarmless() {
        assertNull(monitor.released());
        monitor.requested();
        assertNull(monitor.released(), "a request that never acquired must not create a hold");
        assertNotNull(monitor);
    }
}
