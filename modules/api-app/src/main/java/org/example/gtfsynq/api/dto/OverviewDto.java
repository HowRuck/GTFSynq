package org.example.gtfsynq.api.dto;

import java.time.OffsetDateTime;

/**
 * Row counts shown on the landing page.
 *
 * @param lastTripUpdateAt when a trip update was last seen, or {@code null} when none is held
 * @param staticHealth health of the static feeds
 * @param realtimeHealth health of the realtime feeds
 */
public record OverviewDto(
        long staticFeeds,
        long routes,
        long stops,
        long trips,
        long liveTripUpdates,
        long liveStopTimeUpdates,
        OffsetDateTime lastTripUpdateAt,
        FeedHealthDto staticHealth,
        FeedHealthDto realtimeHealth) {}
