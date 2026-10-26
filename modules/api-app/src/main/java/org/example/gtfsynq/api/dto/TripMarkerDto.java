package org.example.gtfsynq.api.dto;

/**
 * One live trip's position on the map.
 *
 * <p>Carries only what a marker needs, so plotting every trip does not transfer the full trip-update
 * row the paged table shows.
 */
public record TripMarkerDto(
        double lat,
        double lon,
        String routeShortName,
        String tripShortName,
        String routeId,
        String tripId,
        Integer routeType,
        String stopName,
        Integer delaySeconds) {}
