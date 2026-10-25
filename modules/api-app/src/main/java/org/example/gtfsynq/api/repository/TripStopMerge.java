package org.example.gtfsynq.api.repository;

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
        return new StopTimeUpdateDto(
                live.tripUpdateId(),
                live.stopSequence(),
                coalesce(live.stopId(), schedule == null ? null : schedule.stopId()),
                coalesce(live.arrivalTime(), schedule == null ? null : schedule.arrivalTime()),
                coalesce(live.arrivalDelay(), schedule == null ? null : schedule.arrivalDelay()),
                coalesce(live.scheduledArrivalTime(), schedule == null ? null : schedule.scheduledArrivalTime()),
                coalesce(live.departureTime(), schedule == null ? null : schedule.departureTime()),
                coalesce(live.departureDelay(), schedule == null ? null : schedule.departureDelay()),
                coalesce(live.scheduledDepartureTime(), schedule == null ? null : schedule.scheduledDepartureTime()),
                coalesce(live.scheduleRelationship(), schedule == null ? null : schedule.scheduleRelationship()),
                coalesce(live.assignedStopId(), schedule == null ? null : schedule.assignedStopId()),
                coalesce(live.stopName(), schedule == null ? null : schedule.stopName()),
                coalesce(live.lastSeenAt(), schedule == null ? null : schedule.lastSeenAt()),
                true);
    }

    private static <T> T coalesce(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
