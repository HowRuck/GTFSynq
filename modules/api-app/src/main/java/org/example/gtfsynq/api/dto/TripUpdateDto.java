package org.example.gtfsynq.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** A live trip update, joining the hot table with its metadata row. */
public record TripUpdateDto(
        long tripUpdateId,
        String feedId,
        OffsetDateTime feedTs,
        OffsetDateTime lastSeenAt,
        String entityId,
        String tripId,
        String routeId,
        LocalDate startDate,
        LocalTime startTime,
        int stopTimeUpdateCount) {}
