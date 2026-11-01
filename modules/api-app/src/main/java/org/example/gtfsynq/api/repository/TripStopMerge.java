package org.example.gtfsynq.api.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.example.gtfsynq.api.dto.StopTimeUpdateDto;

/**
 * Merges a trip's static schedule with its live stop time updates, keyed by stop sequence.
 *
 * <p>Doing this in Java instead of SQL keeps both queries simple (and index-friendly) and makes the
 * merge rules explicit: schedule rows provide the stop list and scheduled times, live rows overlay
 * every value they carry, and live-only stops (e.g. ADDED stops) are appended in sequence order.
 * A live row that carries a time but no delay is given the delay its time implies against the
 * schedule, so a feed that publishes predictions only (PKP PLK, HSL) still shows a delay where the
 * plan is known.
 */
final class TripStopMerge {

    private TripStopMerge() {}

    /**
     * @param schedule stops from {@code static_stop_times}; {@code realtime == false}
     * @param live     rows from {@code rt_stop_time_updates_hot}; {@code realtime == true}
     * @return one row per stop sequence, ordered ascending
     */
    static List<StopTimeUpdateDto> merge(List<StopTimeUpdateDto> schedule, List<StopTimeUpdateDto> live) {
        var bySequence = new TreeMap<Integer, StopTimeUpdateDto>();
        for (var stop : schedule) {
            bySequence.put(stop.stopSequence(), stop);
        }
        for (var update : live) {
            bySequence.put(update.stopSequence(), overlay(bySequence.get(update.stopSequence()), update));
        }
        return new ArrayList<>(bySequence.values());
    }

    /** Live values win where present; schedule values fill the gaps. */
    private static StopTimeUpdateDto overlay(StopTimeUpdateDto schedule, StopTimeUpdateDto live) {
        var arrivalTime = coalesce(live.arrivalTime(), schedule == null ? null : schedule.arrivalTime());
        var scheduledArrival =
                coalesce(live.scheduledArrivalTime(), schedule == null ? null : schedule.scheduledArrivalTime());
        var departureTime = coalesce(live.departureTime(), schedule == null ? null : schedule.departureTime());
        var scheduledDeparture =
                coalesce(live.scheduledDepartureTime(), schedule == null ? null : schedule.scheduledDepartureTime());

        return new StopTimeUpdateDto(
                live.tripUpdateId(),
                live.stopSequence(),
                coalesce(live.stopId(), schedule == null ? null : schedule.stopId()),
                arrivalTime,
                // A feed that publishes a prediction and no delay (PKP PLK) still implies one: the
                // predicted time minus the plan for the same stop.
                coalesce(live.arrivalDelay(), impliedDelay(arrivalTime, scheduledArrival)),
                scheduledArrival,
                departureTime,
                coalesce(live.departureDelay(), impliedDelay(departureTime, scheduledDeparture)),
                scheduledDeparture,
                coalesce(live.scheduleRelationship(), schedule == null ? null : schedule.scheduleRelationship()),
                coalesce(live.assignedStopId(), schedule == null ? null : schedule.assignedStopId()),
                coalesce(live.stopName(), schedule == null ? null : schedule.stopName()),
                coalesce(live.lat(), schedule == null ? null : schedule.lat()),
                coalesce(live.lon(), schedule == null ? null : schedule.lon()),
                coalesce(live.lastSeenAt(), schedule == null ? null : schedule.lastSeenAt()),
                true);
    }

    /** Delay implied by a prediction against the plan, or {@code null} when either side is unknown. */
    private static Integer impliedDelay(OffsetDateTime predicted, OffsetDateTime scheduled) {
        if (predicted == null || scheduled == null) {
            return null;
        }
        return (int) Duration.between(scheduled, predicted).toSeconds();
    }

    private static <T> T coalesce(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
