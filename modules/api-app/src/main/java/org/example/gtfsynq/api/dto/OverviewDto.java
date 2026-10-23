package org.example.gtfsynq.api.dto;

/** Row counts shown on the landing page. */
public record OverviewDto(
        long staticFeeds, long routes, long stops, long trips, long liveTripUpdates, long liveStopTimeUpdates) {}
