package org.example.gtfsynq.shared.model.dto;

import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.example.gtfsynq.shared.util.FeedHashing;
import org.example.gtfsynq.shared.util.GtfsFeedFormatter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * DTO for a GTFS trip stop time update, including its hash for deduplication.
 *
 * @param tripKey the trip key of this stop time update
 * @param feedId the feed ID of this stop time update
 * @param feedTs the timestamp when this stop time update was received
 * @param stopSequence the stop sequence of this stop time update
 * @param stopId the stop ID of this stop time update
 * @param arrivalTime the arrival time of this stop time update
 * @param arrivalDelay the arrival delay of this stop time update
 * @param scheduledArrivalTime the scheduled arrival time of this stop time update
 * @param departureTime the departure time of this stop time update
 * @param departureDelay the departure delay of this stop time update
 * @param scheduledDepartureTime the scheduled departure time of this stop time update
 * @param scheduleRelationship the schedule relationship of this stop time update
 * @param assignedStopId the assigned stop ID of this stop time update
 * @param hash the hash of this stop time update for deduplication
 */
public record TripStopTimeUpdateDto(
        long tripKey,
        @NonNull String feedId,
        @NonNull Instant feedTs,
        @Nullable Integer stopSequence,
        @Nullable String stopId,
        @Nullable Instant arrivalTime,
        @Nullable Integer arrivalDelay,
        @Nullable Instant scheduledArrivalTime,
        @Nullable Instant departureTime,
        @Nullable Integer departureDelay,
        @Nullable Instant scheduledDepartureTime,
        @Nullable ScheduleRelationship scheduleRelationship,
        @Nullable String assignedStopId,
        long hash) {
    /**
     * Creates a TripStopTimeUpdateDto from a StopTimeUpdate protobuf message
     */
    public static TripStopTimeUpdateDto fromProto(long tripId, String feedId, Instant feedTs, StopTimeUpdate stu) {
        var stopSequence = GtfsFeedFormatter.nullableInteger(stu.hasStopSequence(), stu.getStopSequence());
        var stopId = GtfsFeedFormatter.nullableString(stu.hasStopId(), stu.getStopId());

        Integer arrivalDelay = null;
        Instant arrivalTime = null;
        Instant scheduledArrivalTime = null;

        if (stu.hasArrival()) {
            var arrival = stu.getArrival();
            arrivalDelay = GtfsFeedFormatter.nullableInteger(arrival.hasDelay(), arrival.getDelay());

            arrivalTime = GtfsFeedFormatter.nullableInstant(arrival.hasTime(), arrival.getTime());

            scheduledArrivalTime =
                    GtfsFeedFormatter.nullableInstant(arrival.hasScheduledTime(), arrival.getScheduledTime());
        }

        Integer departureDelay = null;
        Instant departureTime = null;
        Instant scheduledDepartureTime = null;

        if (stu.hasDeparture()) {
            var departure = stu.getDeparture();
            departureDelay = GtfsFeedFormatter.nullableInteger(departure.hasDelay(), departure.getDelay());

            departureTime = GtfsFeedFormatter.nullableInstant(departure.hasTime(), departure.getTime());

            scheduledDepartureTime =
                    GtfsFeedFormatter.nullableInstant(departure.hasScheduledTime(), departure.getScheduledTime());
        }

        var scheduleRelationship = stu.getScheduleRelationship();
        var assignedStopId = GtfsFeedFormatter.nullableString(stu.hasStopId(), stu.getStopId());

        // Creates a hash of metadata fields.
        //
        // Delays are excluded from this hash to ensure stability during deduplication, as they
        // are compared separately against their previous values. An absolute arrival/departure
        // time travels with its delay: a feed that publishes a delay is compared by delay, and
        // its time would only duplicate that. A feed that publishes a time and no delay (HSL)
        // has nothing else to compare, so its time is bucketed into the hash instead.
        var hash = hashMetaFields(
                feedId,
                stopSequence,
                stopId,
                arrivalTime,
                arrivalDelay,
                scheduledArrivalTime,
                departureTime,
                departureDelay,
                scheduledDepartureTime,
                scheduleRelationship,
                assignedStopId);

        return new TripStopTimeUpdateDto(
                tripId,
                feedId,
                feedTs,
                stopSequence,
                stopId,
                arrivalTime,
                arrivalDelay,
                scheduledArrivalTime,
                departureTime,
                departureDelay,
                scheduledDepartureTime,
                scheduleRelationship,
                assignedStopId,
                hash);
    }

    /**
     * Returns a copy with a different stop sequence, recomputing the metadata hash.
     *
     * <p>Used when a feed omits {@code stop_sequence}: the sequence is resolved against the static
     * schedule (or the message order) before the row is written, and the hash has to describe the
     * row that is actually stored.
     *
     * @param sequence the sequence to store
     * @return this instance when the sequence already matches, otherwise a resolved copy
     */
    public TripStopTimeUpdateDto withStopSequence(@NonNull Integer sequence) {
        if (sequence.equals(stopSequence)) {
            return this;
        }
        return new TripStopTimeUpdateDto(
                tripKey,
                feedId,
                feedTs,
                sequence,
                stopId,
                arrivalTime,
                arrivalDelay,
                scheduledArrivalTime,
                departureTime,
                departureDelay,
                scheduledDepartureTime,
                scheduleRelationship,
                assignedStopId,
                hashMetaFields(
                        feedId,
                        sequence,
                        stopId,
                        arrivalTime,
                        arrivalDelay,
                        scheduledArrivalTime,
                        departureTime,
                        departureDelay,
                        scheduledDepartureTime,
                        scheduleRelationship,
                        assignedStopId));
    }

    /**
     * Returns a copy with a different stop id, recomputing the metadata hash.
     *
     * <p>Used for a feed that publishes only {@code stop_sequence}: the id of the stop at that
     * sequence is read from the static schedule, because the position on the map and the stop name
     * in the timeline both come from {@code stops.txt}, not from the sequence. A row the static
     * schedule cannot identify is left without an id rather than guessed at.
     *
     * @param stopId the stop id to store
     * @return this instance when the stop id already matches, otherwise a resolved copy
     */
    public TripStopTimeUpdateDto withStopId(@NonNull String stopId) {
        if (stopId.equals(this.stopId)) {
            return this;
        }
        return new TripStopTimeUpdateDto(
                tripKey,
                feedId,
                feedTs,
                stopSequence,
                stopId,
                arrivalTime,
                arrivalDelay,
                scheduledArrivalTime,
                departureTime,
                departureDelay,
                scheduledDepartureTime,
                scheduleRelationship,
                assignedStopId,
                hashMetaFields(
                        feedId,
                        stopSequence,
                        stopId,
                        arrivalTime,
                        arrivalDelay,
                        scheduledArrivalTime,
                        departureTime,
                        departureDelay,
                        scheduledDepartureTime,
                        scheduleRelationship,
                        assignedStopId));
    }

    /**
     * Hashes the meta fields of a TripStopTimeUpdateDto
     *
     * @param feedId The feed ID
     * @param stopSequence The stop sequence
     * @param stopId The stop ID
     * @param arrivalTime The predicted arrival time
     * @param arrivalDelay The arrival delay
     * @param scheduledArrivalTime The scheduled arrival time
     * @param departureTime The predicted departure time
     * @param departureDelay The departure delay
     * @param scheduledDepartureTime The scheduled departure time
     * @param scheduleRelationship The schedule relationship
     * @param assignedStopId The assigned stop ID
     * @return The hash
     */
    private static long hashMetaFields(
            String feedId,
            Integer stopSequence,
            String stopId,
            Instant arrivalTime,
            Integer arrivalDelay,
            Instant scheduledArrivalTime,
            Instant departureTime,
            Integer departureDelay,
            Instant scheduledDepartureTime,
            ScheduleRelationship scheduleRelationship,
            String assignedStopId) {
        return FeedHashing.encoder()
                .putInteger(feedId.hashCode())
                .putInteger(stopSequence)
                .putString(stopId)
                .putInstant(timeBucket(arrivalTime, arrivalDelay))
                .putInstant(scheduledArrivalTime)
                .putInstant(timeBucket(departureTime, departureDelay))
                .putInstant(scheduledDepartureTime)
                .putString(scheduleRelationship == null ? null : scheduleRelationship.name())
                .putString(assignedStopId)
                .hash();
    }

    /**
     * The comparison value of a predicted time: the minute it falls in, but only when the feed
     * publishes no delay for it.
     *
     * <p>Deduplication keeps a stop when its metadata changes or its delay moves by more than a
     * minute. A feed that publishes only {@code time} (HSL) has neither, so without this bucket
     * its predictions compare equal forever: the row is written once, ages out of the hot table,
     * and is never rewritten. Bucketing to the minute gives those feeds the same sensitivity as
     * the delay comparison, at the cost of one write per changed prediction rather than per
     * sixty-second delay jump.
     *
     * @param time the predicted arrival or departure time, {@code null} when not published
     * @param delay the delay for the same event, {@code null} when not published
     * @return the minute bucket, or {@code null} when the time must not take part in the hash
     */
    private static Instant timeBucket(Instant time, Integer delay) {
        return delay != null || time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }
}
