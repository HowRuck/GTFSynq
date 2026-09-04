package org.example.gtfsynq.shared.model.dto;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a trip update, including the trip descriptor and stop time updates.
 *
 * @param tripDescriptor the trip descriptor
 * @param stopTimeUpdates the stop time updates
 */
public record TripUpdateDto(
        TripDescriptorDto tripDescriptor,
        List<TripStopTimeUpdateDto> stopTimeUpdates) {
    /**
     * Converts a FeedEntity to a TripUpdateDto
     *
     * @param entity the feed entity
     * @param feedId the feed id
     * @param feedTs the timestamp of the feed
     * @return the trip update dto, or null if the entity does not have a trip update
     */
    public static TripUpdateDto fromEntity(FeedEntity entity, String feedId, Instant feedTs) {
        if (!entity.hasTripUpdate()) return null;

        var update = entity.getTripUpdate();

        // Convert trip descriptor
        var tripDescriptor = TripDescriptorDto.fromEntity(update.getTrip(), feedId, entity.getId(), feedTs);

        // Individually convert Stop Time Updates
        var stopTimeUpdates = new ArrayList<TripStopTimeUpdateDto>(update.getStopTimeUpdateCount());
        for (var index = 0; index < update.getStopTimeUpdateCount(); index++) {
            var stu = update.getStopTimeUpdate(index);

            stopTimeUpdates.add(TripStopTimeUpdateDto.fromProto(tripDescriptor.id(), feedId, feedTs, stu));
        }

        return new TripUpdateDto(tripDescriptor, stopTimeUpdates);
    }
}
