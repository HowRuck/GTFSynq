package org.example.gtfsynq.store.adapter.outbound.database;

import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;

/**
 * Derives the effective delay and event times of a stop-time update from the static schedule at
 * write time.
 *
 * <p>This is the same rule the read path uses, moved to the boundary where the row is stored so the
 * history and the aggregates see one value:
 *
 * <ol>
 *   <li>published {@code arrival_delay}, else published {@code departure_delay};
 *   <li>otherwise the prediction minus the static plan for the same stop;
 *   <li>otherwise {@code null}.
 * </ol>
 *
 * <p>The published columns stay untouched. A row that carries a time keeps that time as its
 * effective time; a row that carries only a delay gets the plan shifted by that delay. Both need the
 * descriptor's service date, the stop's GTFS time (with its day overflow) and the agency time zone,
 * which the caller loads once per flush. A trip that cannot be tied to a static trip - HSL sends no
 * {@code trip_id} - keeps the published values and gets no derived delay.
 *
 * <p>{@code SKIPPED} and {@code NO_DATA} stops keep published times and delays but never get a plan
 * derived time: they are not service, so inventing a clock time for them would be misleading.
 */
@ApplicationScoped
public class EffectiveDelayResolver {

    /**
     * Trips that need a static lookup because at least one row is missing a time or a delay.
     *
     * <p>A row that already carries both times and both delays only needs its published values
     * copied into the effective columns, which does not require the schedule.
     *
     * @param batch drained sink batch
     * @return the feed-scoped trips whose rows need the static plan
     */
    static Set<TripKey> keysNeedingLookup(List<TripUpdateDto> batch) {
        var keys = new HashSet<TripKey>();
        for (var update : batch) {
            var descriptor = update.tripDescriptor();
            if (!hasTripIdentity(descriptor) || descriptor.startDate() == null) {
                continue;
            }
            var rows = update.stopTimeUpdates();
            if (rows == null) {
                continue;
            }
            for (var row : rows) {
                if (needsPlan(row)) {
                    keys.add(new TripKey(descriptor.feedId(), descriptor.tripId()));
                    break;
                }
            }
        }
        return keys;
    }

    /**
     * Fills the effective delay and event times of every row.
     *
     * <p>Trips absent from {@code schedules} still get their published values copied, so a feed
     * that publishes a delay but has no static match is not left with an empty effective column.
     *
     * @param batch drained sink batch, after stop sequences have been resolved
     * @param schedules static stops by feed-scoped trip key
     * @return the batch with effective values where they could be determined
     */
    List<TripUpdateDto> resolve(List<TripUpdateDto> batch, Map<TripKey, List<StaticStopTime>> schedules) {
        var resolved = new ArrayList<TripUpdateDto>(batch.size());
        for (var update : batch) {
            var descriptor = update.tripDescriptor();
            var rows = update.stopTimeUpdates();
            if (descriptor == null || rows == null || rows.isEmpty()) {
                resolved.add(update);
                continue;
            }

            var schedule = hasTripIdentity(descriptor)
                    ? schedules.get(new TripKey(descriptor.feedId(), descriptor.tripId()))
                    : null;
            var stopsBySequence = bySequence(schedule);
            var zone = zoneOf(schedule);
            var serviceDate = descriptor.startDate();
            var changed = false;
            var effectiveRows = new ArrayList<TripStopTimeUpdateDto>(rows.size());

            for (var row : rows) {
                var stop = row.stopSequence() == null ? null : stopsBySequence.get(row.stopSequence());
                var effective = derive(row, stop, serviceDate, zone);
                effectiveRows.add(effective);
                changed |= effective != row;
            }

            resolved.add(changed ? new TripUpdateDto(descriptor, effectiveRows) : update);
        }
        return resolved;
    }

    private static TripStopTimeUpdateDto derive(
            TripStopTimeUpdateDto row, StaticStopTime stop, LocalDate serviceDate, ZoneId zone) {
        var skipped = isSkippedOrNoData(row);
        var scheduledArrival = scheduledAt(serviceDate, stop, zone, true);
        var scheduledDeparture = scheduledAt(serviceDate, stop, zone, false);

        var derivedArrival = skipped ? null : impliedDelay(row.arrivalTime(), scheduledArrival);
        var derivedDeparture = skipped ? null : impliedDelay(row.departureTime(), scheduledDeparture);
        // Published values win over derived ones, and the arrival event wins over departure,
        // matching the read path's COALESCE order.
        var delay = firstNonNull(row.arrivalDelay(), row.departureDelay(), derivedArrival, derivedDeparture);

        // An event without a time of its own takes the plan plus its own published delay, else the
        // stop's delay: GTFS-RT lets one delay describe both events of a stop.
        // Each event keeps its own delay (published, else implied by its own time), and falls back
        // to the stop's delay: GTFS-RT lets one delay describe both events of a stop.
        var arrivalDelay = skipped ? row.arrivalDelay() : firstNonNull(row.arrivalDelay(), derivedArrival, delay);
        var departureDelay =
                skipped ? row.departureDelay() : firstNonNull(row.departureDelay(), derivedDeparture, delay);

        var effectiveArrival = row.arrivalTime() != null
                ? row.arrivalTime()
                : skipped ? null : plusDelay(scheduledArrival, arrivalDelay);
        var effectiveDeparture = row.departureTime() != null
                ? row.departureTime()
                : skipped ? null : plusDelay(scheduledDeparture, departureDelay);

        return row.withEffectiveValues(delay, effectiveArrival, effectiveDeparture, arrivalDelay, departureDelay);
    }

    private static boolean needsPlan(TripStopTimeUpdateDto row) {
        if (isSkippedOrNoData(row)) {
            return false;
        }
        return row.arrivalTime() == null
                || row.departureTime() == null
                || row.arrivalDelay() == null
                || row.departureDelay() == null;
    }

    private static boolean hasTripIdentity(TripDescriptorDto descriptor) {
        return descriptor != null && descriptor.feedId() != null && descriptor.tripId() != null;
    }

    private static boolean isSkippedOrNoData(TripStopTimeUpdateDto row) {
        var relationship = row.scheduleRelationship();
        return relationship == ScheduleRelationship.SKIPPED || relationship == ScheduleRelationship.NO_DATA;
    }

    private static Map<Integer, StaticStopTime> bySequence(List<StaticStopTime> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return Map.of();
        }
        var bySequence = new HashMap<Integer, StaticStopTime>(schedule.size() * 2 + 16);
        for (var stop : schedule) {
            bySequence.putIfAbsent(stop.sequence(), stop);
        }
        return bySequence;
    }

    private static ZoneId zoneOf(List<StaticStopTime> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return null;
        }
        var timezone = schedule.getFirst().timezone();
        try {
            return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static Instant scheduledAt(LocalDate serviceDate, StaticStopTime stop, ZoneId zone, boolean arrival) {
        if (stop == null || serviceDate == null || zone == null) {
            return null;
        }
        var time = arrival ? stop.arrivalTime() : stop.departureTime();
        if (time == null) {
            return null;
        }
        var overflowDays = arrival ? stop.arrivalOverflowDays() : stop.departureOverflowDays();
        var days = overflowDays == null ? 0 : overflowDays;
        return serviceDate.plusDays(days).atTime(time).atZone(zone).toInstant();
    }

    private static Integer impliedDelay(Instant predicted, Instant scheduled) {
        if (predicted == null || scheduled == null) {
            return null;
        }
        return (int) Duration.between(scheduled, predicted).toSeconds();
    }

    private static Instant plusDelay(Instant scheduled, Integer delay) {
        return scheduled == null || delay == null ? null : scheduled.plusSeconds(delay);
    }

    private static Integer firstNonNull(Integer... candidates) {
        for (var candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
