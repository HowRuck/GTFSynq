package org.example.gtfsynq.api.dto;

/** One row from {@code static_stops}. */
public record StopDto(
        String feedId,
        String stopId,
        String stopName,
        Double lat,
        Double lon,
        Integer locationType,
        String parentStation) {}
