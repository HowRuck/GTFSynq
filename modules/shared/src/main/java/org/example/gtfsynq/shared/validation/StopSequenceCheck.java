package org.example.gtfsynq.shared.validation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects a decreasing {@code stop_sequence} within a trip while streaming {@code stop_times.txt}.
 *
 * <p>Correctly built feeds group the rows of a trip together, and consecutive <em>runs</em> of trip
 * ids are therefore strictly increasing. That makes the common case O(1) to check: compare against
 * the previous row while the trip is unchanged. If a run transition goes backwards, the file is not
 * grouped and a single running cursor would silently miss decreases, so the check escalates once to
 * per-trip bookkeeping.
 *
 * <p>Escalation is sound because every row seen before it belonged to a contiguous run: nothing was
 * compared under a wrong assumption, and the runs closed so far are replayed into the per-trip map
 * from a small ring buffer, so a trip that reappears after escalating is still compared against its
 * true last sequence. The buffer bounds what can be replayed; a malformed file interleaving more
 * than {@link #RECENT_RUNS} distinct runs loses comparisons only for trips last seen before that
 * window.
 *
 * <p>Per-trip state is capped at {@code maxTrackedTrips} so an adversarial feed cannot exhaust
 * memory; on breach the check keeps running for the trips already tracked and reports {@link
 * #complete()} as {@code false}.
 */
final class StopSequenceCheck {

    /** Default cap on distinct trips tracked in the escalated mode. */
    static final int DEFAULT_MAX_TRACKED_TRIPS = 2_000_000;

    /** How many closed runs are remembered so escalation can replay them into the per-trip map. */
    private static final int RECENT_RUNS = 1024;

    private final int maxTrackedTrips;

    private final Deque<RunEnd> recentRuns = new ArrayDeque<>();

    private Map<String, Long> lastSequenceByTrip;

    private String runTripId;

    private long runLastSequence = -1;

    private boolean complete = true;

    StopSequenceCheck(int maxTrackedTrips) {
        this.maxTrackedTrips = maxTrackedTrips;
    }

    /** Whether every row was compared against its trip's previous sequence. */
    boolean complete() {
        return complete;
    }

    /**
     * Records one {@code stop_times} row.
     *
     * @param tripId trip the row belongs to, never {@code null}
     * @param stopSequence parsed {@code stop_sequence}, never negative in well-formed rows
     * @return {@code true} when the sequence is below the previous one seen for the same trip
     */
    boolean outOfOrder(String tripId, long stopSequence) {
        return lastSequenceByTrip == null ? groupedCheck(tripId, stopSequence) : perTripCheck(tripId, stopSequence);
    }

    /** O(1) path: compare within the current run, escalate if run transitions go backwards. */
    private boolean groupedCheck(String tripId, long stopSequence) {
        if (tripId.equals(runTripId)) {
            boolean decreased = stopSequence < runLastSequence;
            runLastSequence = stopSequence;
            return decreased;
        }
        if (runTripId != null) {
            recentRuns.addLast(new RunEnd(runTripId, runLastSequence));
            if (recentRuns.size() > RECENT_RUNS) {
                recentRuns.removeFirst();
            }
            // A grouped feed's trip ids only ever increase across runs; anything else means the
            // file is not grouped, and the cursor can no longer be trusted.
            if (tripId.compareTo(runTripId) < 0) {
                escalate();
                return perTripCheck(tripId, stopSequence);
            }
        }
        runTripId = tripId;
        runLastSequence = stopSequence;
        return false;
    }

    /** Fallback path once the file proved not to be grouped. */
    private boolean perTripCheck(String tripId, long stopSequence) {
        var previous = lastSequenceByTrip.get(tripId);
        if (previous == null) {
            // Unknown trip: either the first sighting or the cap was reached, in which case the
            // row cannot be compared and completeness is already reported as false.
            track(tripId, stopSequence);
            return false;
        }
        track(tripId, stopSequence);
        return stopSequence < previous;
    }

    private void track(String tripId, long stopSequence) {
        if (lastSequenceByTrip.size() >= maxTrackedTrips && !lastSequenceByTrip.containsKey(tripId)) {
            complete = false;
            return;
        }
        lastSequenceByTrip.put(tripId, stopSequence);
    }

    private void escalate() {
        lastSequenceByTrip = new HashMap<>(Math.min(recentRuns.size() * 2, RECENT_RUNS * 2));
        for (var run : recentRuns) {
            track(run.tripId(), run.lastSequence());
        }
        recentRuns.clear();
    }

    private record RunEnd(String tripId, long lastSequence) {}
}
