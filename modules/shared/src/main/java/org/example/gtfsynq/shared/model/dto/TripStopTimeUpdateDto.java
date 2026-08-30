package org.example.gtfsynq.shared.model.dto;

import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship;
import java.time.Instant;
import org.example.gtfsynq.shared.util.FeedHashing;
import org.example.gtfsynq.shared.util.GtfsFeedFormatter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * DTO for a GTFS trip stop time update, including its hash for deduplication
 */
public record TripStopTimeUpdateDto(
        /** The trip key of this stop time update */
        long tripKey,
        /** The feed ID of this stop time update */
        @NonNull String feedId,
        /** The timestamp when this stop time update was received */
        @NonNull Instant feedTs,
        /** The stop sequence of this stop time update */
        @NonNull Integer stopSequence,
        /** The stop ID of this stop time update */
        @NonNull String stopId,
        /** The arrival time of this stop time update */
        @Nullable Instant arrivalTime,
        /** The arrival delay of this stop time update */
        @Nullable Integer arrivalDelay,
        /** The scheduled arrival time of this stop time update */
        @Nullable Instant scheduledArrivalTime,
        /** The departure time of this stop time update */
        @Nullable Instant departureTime,
        /** The departure delay of this stop time update */
        @Nullable Integer departureDelay,
        /** The scheduled departure time of this stop time update */
        @Nullable Instant scheduledDepartureTime,
        /** The schedule relationship of this stop time update */
        @Nullable ScheduleRelationship scheduleRelationship,
        /** The assigned stop ID of this stop time update */
        @Nullable String assignedStopId,
        /** The hash of this stop time update for deduplication */
        long hash) {
    /**
     * Creates a TripStopTimeUpdateDto from a StopTimeUpdate protobuf message
     */
    public static TripStopTimeUpdateDto fromProto(long tripId, String feedId, Instant feedTs, StopTimeUpdate stu) {
        var stopSequence = GtfsFeedFormatter.nullableInteger(stu.hasStopSequence(), stu.getStopSequence());
        var stopId = GtfsFeedFormatter.nullableString(stu.hasStopId(), stu.getStopId());

        var arrival = stu.hasArrival() ? stu.getArrival() : null;

        Integer arrivalDelay = null;
        Instant arrivalTime = null;
        Instant scheduledArrivalTime = null;

        if (arrival != null) {
            arrivalDelay = GtfsFeedFormatter.nullableInteger(arrival.hasDelay(), arrival.getDelay());

            arrivalTime = GtfsFeedFormatter.nullableInstant(arrival.hasTime(), arrival.getTime());

            scheduledArrivalTime =
                    GtfsFeedFormatter.nullableInstant(arrival.hasScheduledTime(), arrival.getScheduledTime());
        }

        var departure = stu.hasDeparture() ? stu.getDeparture() : null;

        Integer departureDelay = null;
        Instant departureTime = null;
        Instant scheduledDepartureTime = null;

        if (departure != null) {
            departureDelay = GtfsFeedFormatter.nullableInteger(departure.hasDelay(), departure.getDelay());

            departureTime = GtfsFeedFormatter.nullableInstant(departure.hasTime(), departure.getTime());

            scheduledDepartureTime =
                    GtfsFeedFormatter.nullableInstant(departure.hasScheduledTime(), departure.getScheduledTime());
        }

        var scheduleRelationship = stu.getScheduleRelationship();
        var assignedStopId = GtfsFeedFormatter.nullableString(stu.hasStopId(), stu.getStopId());

        /**
         * Creates a hash of metadata fields.
         *
         * Delays (and their associated arrival/departure times) are explicitly excluded
         * from this hash to ensure stability during deduplication, as these values are
         * compared separately against their previous values.
         *
         * This hashes everything in the object EXEPT the delays and directly associated values in order to keep hash stable
         */
        var hash = hashMetaFields(
                feedId,
                stopSequence,
                stopId,
                scheduledArrivalTime,
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
     * Hashes the meta fields of a TripStopTimeUpdateDto
     *
     * @param feedId The feed ID
     * @param stopSequence The stop sequence
     * @param stopId The stop ID
     * @param scheduledArrivalTime The scheduled arrival time
     * @param scheduledDepartureTime The scheduled departure time
     * @param scheduleRelationship The schedule relationship
     * @param assignedStopId The assigned stop ID
     * @return The hash
     */
    private static long hashMetaFields(
            String feedId,
            Integer stopSequence,
            String stopId,
            Instant scheduledArrivalTime,
            Instant scheduledDepartureTime,
            ScheduleRelationship scheduleRelationship,
            String assignedStopId) {
        return FeedHashing.encoder()
                .putInteger(feedId.hashCode())
                .putInteger(stopSequence)
                .putString(stopId)
                .putInstant(scheduledArrivalTime)
                .putInstant(scheduledDepartureTime)
                .putString(scheduleRelationship == null ? null : scheduleRelationship.name())
                .putString(assignedStopId)
                .hash();
    }
}
