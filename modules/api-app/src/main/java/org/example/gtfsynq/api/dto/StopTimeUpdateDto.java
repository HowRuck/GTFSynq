package org.example.gtfsynq.api.dto;

import java.time.OffsetDateTime;

/** One current stop time update of a live trip. */
public record StopTimeUpdateDto(
        long tripUpdateId,
        int stopSequence,
        String stopId,
        OffsetDateTime arrivalTime,
        Integer arrivalDelay,
        OffsetDateTime scheduledArrivalTime,
        OffsetDateTime departureTime,
        Integer departureDelay,
        OffsetDateTime scheduledDepartureTime,
        String scheduleRelationship,
        String assignedStopId) {}
