package org.example.gtfsynq.api.dto;

import java.time.OffsetDateTime;

/**
 * Row counts shown on the landing page.
 *
 * @param lastTripUpdateAt when a trip update was last seen, or {@code null} when none is held
 */
public record OverviewDto(
        long staticFeeds,
        long routes,
        long stops,
        long trips,
        long liveTripUpdates,
        long liveStopTimeUpdates,
        OffsetDateTime lastTripUpdateAt) {}
