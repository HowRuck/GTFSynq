package org.example.gtfsynq.api.dto;

/** One row from {@code static_trips}. */
public record TripDto(String feedId, String tripId, String routeId, String serviceId, Integer directionId) {}
