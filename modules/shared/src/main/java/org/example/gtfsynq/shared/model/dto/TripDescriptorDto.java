package org.example.gtfsynq.shared.model.dto;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.example.gtfsynq.shared.util.FeedHashing;
import org.example.gtfsynq.shared.util.GtfsFeedFormatter;

/**
 * DTO for a GTFS trip descriptor, including its hash for deduplication.
 *
 * @param id the stable ID of this trip descriptor
 * @param entityId the entity ID of this trip descriptor
 * @param feedId the feed ID of this trip descriptor
 * @param feedTs the timestamp when this trip descriptor was received
 * @param tripId the trip ID of this trip descriptor
 * @param routeId the route ID of this trip descriptor
 * @param directionId the direction ID of this trip descriptor
 * @param startDate the start date of this trip descriptor
 * @param startTime the start time of this trip descriptor
 * @param startTimeOverflowDays the overflow days for the start time of this trip descriptor
 * @param hash the hash of this trip descriptor for deduplication
 */
public record TripDescriptorDto(
        long id,
        String entityId,
        String feedId,
        Instant feedTs,
        String tripId,
        String routeId,
        Integer directionId,
        LocalDate startDate,
        LocalTime startTime,
        Short startTimeOverflowDays,
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
