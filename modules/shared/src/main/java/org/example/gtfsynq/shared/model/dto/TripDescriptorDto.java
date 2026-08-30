package org.example.gtfsynq.shared.model.dto;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.example.gtfsynq.shared.util.FeedHashing;
import org.example.gtfsynq.shared.util.GtfsFeedFormatter;

/**
 * DTO for a GTFS trip descriptor, including its hash for deduplication
 */
public record TripDescriptorDto(
        /** The stable ID of this trip descriptor */
        long id,
        /** The entity ID of this trip descriptor */
        String entityId,
        /** The feed ID of this trip descriptor */
        String feedId,
        /** The timestamp when this trip descriptor was received */
        Instant feedTs,
        /** The trip ID of this trip descriptor */
        String tripId,
        /** The route ID of this trip descriptor */
        String routeId,
        /** The direction ID of this trip descriptor */
        Integer directionId,
        /** The start date of this trip descriptor */
        LocalDate startDate,
        /** The start time of this trip descriptor */
        LocalTime startTime,
        /** The overflow days for the start time of this trip descriptor */
        Short startTimeOverflowDays,
        /** The hash of this trip descriptor for deduplication */
        long hash) {
    /**
     * Creates a TripDescriptorDto from a TripDescriptor entity
     */
    public static TripDescriptorDto fromEntity(
            TripDescriptor tripDescriptor, String feedId, String entityId, Instant feedTs) {
        if (tripDescriptor == null) return null;

        var hash = FeedHashing.hashBytes(tripDescriptor.toByteArray());

        var tripId = GtfsFeedFormatter.nullableString(tripDescriptor.hasTripId(), tripDescriptor.getTripId());
        var routeId = GtfsFeedFormatter.nullableString(tripDescriptor.hasRouteId(), tripDescriptor.getRouteId());
        var startDate = GtfsFeedFormatter.nullableDate(tripDescriptor.hasStartDate(), tripDescriptor.getStartDate());
        var startTimePair =
                GtfsFeedFormatter.nullableTime(tripDescriptor.hasStartTime(), tripDescriptor.getStartTime());
        var startTime = (startTimePair != null) ? startTimePair.value0() : null;
        var startTimeOverflowDays = (startTimePair != null) ? startTimePair.value1() : null;

        var directionId =
                GtfsFeedFormatter.nullableInteger(tripDescriptor.hasDirectionId(), tripDescriptor.getDirectionId());
        var tripKeyBytes = GtfsFeedFormatter.buildKey(
                        feedId == null ? "" : feedId,
                        entityId == null ? "" : entityId,
                        routeId == null ? "" : routeId,
                        startDate == null ? "" : startDate.toString(),
                        startTime == null ? "" : startTime.toString())
                .getBytes();

        var tripKeyBytesHash = FeedHashing.hashBytes(tripKeyBytes);

        return new TripDescriptorDto(
                tripKeyBytesHash,
                entityId,
                feedId,
                feedTs,
                tripId,
                routeId,
                directionId,
                startDate,
                startTime,
                startTimeOverflowDays,
                hash);
    }
}
