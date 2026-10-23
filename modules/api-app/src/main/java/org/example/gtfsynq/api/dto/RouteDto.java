package org.example.gtfsynq.api.dto;

/** One row from {@code static_routes}. */
public record RouteDto(
        String feedId,
        String routeId,
        String shortName,
        String longName,
        int routeType,
        String color,
        String textColor) {}
