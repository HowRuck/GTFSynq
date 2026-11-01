package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;

/**
 * Completes the identifiers of a stop-time update from the static schedule: the sequence when the
 * feed publishes only {@code stop_id}, and the stop id when it publishes only {@code stop_sequence}.
 *
 * <p>GTFS Realtime allows a {@code StopTimeUpdate} to carry either field, but {@code stop_id} alone
 * is ambiguous: a loop route can visit the same stop twice in one trip. The hot table keys rows by
 * sequence, so an update without one cannot be stored - and some large producers (SNCF, HSL) never
 * publish it, which used to drop every stop-time row on the floor. In the other direction, the map
 * and the stop timeline need a stop id, which a sequence-only producer (PKP PLK's long-distance
 * feed) never publishes.
 *
 * <p>Sequences are resolved with three rules, applied in order:
 *
 * <ol>
 *   <li>a published sequence is kept as is;
 *   <li>otherwise the stop is matched against the trip's {@code static_stop_times}, walking the
 *       message in order so a repeated stop resolves to successive visits;
 *   <li>if any stop of the trip cannot be matched - an added trip, a frequency-based descriptor
 *       without a {@code trip_id}, or a feed whose static revision is not loaded - the whole
 *       message falls back to its own order (1..n), which keeps rows unique and ordered.
 * </ol>
 *
 * <p>A missing stop id is then filled from the static schedule by its sequence, but only when the
 * sequences are trustworthy: a published sequence, or one matched against the static schedule. The
 * synthetic numbers of rule 3 describe message order, not stops, so a lookup by them would name the
 * wrong stop and must not happen.
 *
 * <p>Resolution happens at flush time, off the Kafka consumer thread, so the database lookup never
 * sits inside the acknowledgement path.
 */
@ApplicationScoped
@Slf4j
public class StopSequenceResolver {

    /** One static stop of a trip; lists are ordered by {@link #sequence()}. */
    public record StaticStopTime(String stopId, int sequence) {}

    /** Feed-scoped trip identity: trip ids are only unique inside their feed. */
    public record TripKey(String feedId, String tripId) {}

    /** Loads the ordered static stops of the given trips, omitting trips the feed does not have. */
    @FunctionalInterface
    public interface StaticStopTimes {

        Map<TripKey, List<StaticStopTime>> load(Collection<TripKey> keys);
    }

    private final StaticStopTimes staticStopTimes;

    @Inject
    public StopSequenceResolver(StaticStopTimes staticStopTimes) {
        this.staticStopTimes = staticStopTimes;
    }

    /**
     * Resolves the missing identifiers of a batch, leaving complete updates untouched.
     *
     * @param batch drained sink batch, in message order per trip
     * @return the same updates with every stop sequence, and every stop id the schedule knows, set
     */
    public List<TripUpdateDto> resolve(List<TripUpdateDto> batch) {
        var keys = keysNeedingLookup(batch);
        var staticStops = keys.isEmpty() ? Map.<TripKey, List<StaticStopTime>>of() : staticStopTimes.load(keys);

        var resolved = new ArrayList<TripUpdateDto>(batch.size());
        var needing = 0;
        var fallbacks = 0;
        var identified = 0;
        for (var update : batch) {
            var rows = update.stopTimeUpdates();
            if (rows == null || rows.isEmpty()) {
                resolved.add(update);
                continue;
            }

            var key = keyOf(update);
            var tripStops = key == null ? null : staticStops.get(key);
            var settled = rows;
            // Synthetic message-order sequences describe no stop, so they must not drive a lookup.
            var sequencesTrusted = true;

            if (needsSequence(rows)) {
                needing++;
                var matched = tripStops == null ? null : resolveAgainstStatic(rows, tripStops);
                if (matched != null) {
                    settled = matched;
                } else {
                    settled = byMessageOrder(rows);
                    sequencesTrusted = false;
                    fallbacks++;
                }
            }

            if (sequencesTrusted && tripStops != null && needsStopId(settled)) {
                var named = nameStops(settled, tripStops);
                if (named != null) {
                    settled = named;
                    identified++;
                }
            }

            resolved.add(settled == rows ? update : new TripUpdateDto(update.tripDescriptor(), settled));
        }

        if (needing > 0 || identified > 0) {
            log.debug(
                    "Resolved stop sequences for {} of {} updates that needed one from the static"
                            + " schedule, {} of them from message order; named the stops of {} updates"
                            + " that published only a sequence",
                    needing - fallbacks,
                    needing,
                    fallbacks,
                    identified);
        }
        return resolved;
    }

    /**
     * Matches each update against the trip's static stops, advancing a cursor so a repeated stop
     * resolves to the next visit rather than always the first.
     *
     * @return the resolved rows, or {@code null} when any stop has no static match
     */
    private static List<TripStopTimeUpdateDto> resolveAgainstStatic(
            List<TripStopTimeUpdateDto> rows, List<StaticStopTime> tripStops) {
        var resolved = new ArrayList<TripStopTimeUpdateDto>(rows.size());
        var cursor = 0;
        var lastSequence = Integer.MIN_VALUE;

        for (var row : rows) {
            if (row.stopSequence() != null) {
                resolved.add(row);
                lastSequence = Math.max(lastSequence, row.stopSequence());
                continue;
            }

            var match = nextMatch(tripStops, row.stopId(), cursor, lastSequence);
            if (match < 0) {
                return null;
            }
            var sequence = tripStops.get(match).sequence();
            resolved.add(row.withStopSequence(sequence));
            lastSequence = sequence;
            cursor = match + 1;
        }
        return resolved;
    }

    /** Index of the first stop after {@code from} with the id and a sequence above the cursor. */
    private static int nextMatch(List<StaticStopTime> tripStops, String stopId, int from, int afterSequence) {
        if (stopId == null) {
            return -1;
        }
        for (var index = from; index < tripStops.size(); index++) {
            var stop = tripStops.get(index);
            if (stop.sequence() > afterSequence && stopId.equals(stop.stopId())) {
                return index;
            }
        }
        return -1;
    }

    /** Sequences the rows 1..n in message order, which keeps them unique when nothing can match. */
    private static List<TripStopTimeUpdateDto> byMessageOrder(List<TripStopTimeUpdateDto> rows) {
        var resolved = new ArrayList<TripStopTimeUpdateDto>(rows.size());
        for (var index = 0; index < rows.size(); index++) {
            resolved.add(rows.get(index).withStopSequence(index + 1));
        }
        return resolved;
    }

    /**
     * Names the stops a sequence-only feed leaves unidentified, by looking each published sequence
     * up in the static schedule.
     *
     * @return the rows with the ids the schedule knows, or {@code null} when it knows none of them
     */
    private static List<TripStopTimeUpdateDto> nameStops(
            List<TripStopTimeUpdateDto> rows, List<StaticStopTime> tripStops) {
        var idBySequence = new HashMap<Integer, String>(tripStops.size() * 2 + 16);
        for (var stop : tripStops) {
            idBySequence.putIfAbsent(stop.sequence(), stop.stopId());
        }

        var named = new ArrayList<TripStopTimeUpdateDto>(rows.size());
        var changed = false;
        for (var row : rows) {
            var stopId =
                    row.stopId() == null && row.stopSequence() != null ? idBySequence.get(row.stopSequence()) : null;
            if (stopId == null) {
                named.add(row);
                continue;
            }
            named.add(row.withStopId(stopId));
            changed = true;
        }
        return changed ? named : null;
    }

    private static Set<TripKey> keysNeedingLookup(List<TripUpdateDto> batch) {
        var keys = new HashSet<TripKey>();
        for (var update : batch) {
            var rows = update.stopTimeUpdates();
            var key = keyOf(update);
            if (key != null && rows != null && (needsSequence(rows) || needsStopId(rows))) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static TripKey keyOf(TripUpdateDto update) {
        var descriptor = update.tripDescriptor();
        if (descriptor == null || descriptor.feedId() == null || descriptor.tripId() == null) {
            return null;
        }
        return new TripKey(descriptor.feedId(), descriptor.tripId());
    }

    private static boolean needsSequence(List<TripStopTimeUpdateDto> rows) {
        for (var row : rows) {
            if (row.stopSequence() == null) {
                return true;
            }
        }
        return false;
    }

    /** Whether a row is identified only by a sequence, which the static schedule can name. */
    private static boolean needsStopId(List<TripStopTimeUpdateDto> rows) {
        for (var row : rows) {
            if (row.stopId() == null && row.stopSequence() != null) {
                return true;
            }
        }
        return false;
    }
}
