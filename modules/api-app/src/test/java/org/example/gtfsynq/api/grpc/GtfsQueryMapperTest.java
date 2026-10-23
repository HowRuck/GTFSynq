package org.example.gtfsynq.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.example.gtfsynq.api.dto.PageResponse;
import org.example.gtfsynq.api.dto.StaticFeedDto;
import org.example.gtfsynq.api.dto.StopTimeUpdateDto;
import org.example.gtfsynq.api.dto.TripUpdateDto;
import org.junit.jupiter.api.Test;

class GtfsQueryMapperTest {

    @Test
    void mapsStaticDisplayNamesOnTripUpdates() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdate("Trip 1", "10", "Central - North")))
                .getItems(0);

        assertTrue(update.hasTripShortName());
        assertTrue(update.hasRouteShortName());
        assertTrue(update.hasRouteLongName());
        assertEquals("Trip 1", update.getTripShortName());
        assertEquals("10", update.getRouteShortName());
        assertEquals("Central - North", update.getRouteLongName());
    }

    @Test
    void leavesStaticDisplayNamesAbsentWhenNoStaticRowMatches() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdate(null, null, null)))
                .getItems(0);

        assertFalse(update.hasTripShortName());
        assertFalse(update.hasRouteShortName());
        assertFalse(update.hasRouteLongName());
    }

    @Test
    void mapsStaticStopNameOnStopTimeUpdates() {
        var update = GtfsQueryMapper.stopTimeUpdatesReply(List.of(stopTimeUpdate("Central Station")))
                .getItems(0);

        assertTrue(update.hasStopName());
        assertEquals("Central Station", update.getStopName());
    }

    @Test
    void leavesStaticStopNameAbsentWhenNoStaticRowMatches() {
        var update = GtfsQueryMapper.stopTimeUpdatesReply(List.of(stopTimeUpdate(null)))
                .getItems(0);

        assertFalse(update.hasStopName());
    }

    @Test
    void mapsAgencyNamesOnStaticFeeds() {
        var feed = GtfsQueryMapper.staticFeedsReply(staticFeedPage(List.of("MTA", "NJ Transit")))
                .getItems(0);

        assertEquals(List.of("MTA", "NJ Transit"), feed.getAgencyNamesList());
    }

    @Test
    void leavesAgencyNamesEmptyWhenNoAgencyRowWasLoaded() {
        var feed = GtfsQueryMapper.staticFeedsReply(staticFeedPage(List.of())).getItems(0);

        assertTrue(feed.getAgencyNamesList().isEmpty());
    }

    private static PageResponse<TripUpdateDto> page(TripUpdateDto update) {
        return new PageResponse<>(List.of(update), 0, 25, 1, 1);
    }

    private static PageResponse<StaticFeedDto> staticFeedPage(List<String> agencyNames) {
        var feed = new StaticFeedDto(
                "feed",
                "sha256",
                "bucket",
                "object-key",
                null,
                1024,
                OffsetDateTime.parse("2026-10-23T10:00:00Z"),
                OffsetDateTime.parse("2026-10-23T10:01:00Z"),
                agencyNames);
        return new PageResponse<>(List.of(feed), 0, 25, 1, 1);
    }

    private static TripUpdateDto tripUpdate(String tripShortName, String routeShortName, String routeLongName) {
        return new TripUpdateDto(
                1,
                "feed",
                OffsetDateTime.parse("2026-10-23T10:00:00Z"),
                OffsetDateTime.parse("2026-10-23T10:01:00Z"),
                "entity",
                "trip-id",
                "route-id",
                null,
                null,
                0,
                null,
                tripShortName,
                routeShortName,
                routeLongName);
    }

    private static StopTimeUpdateDto stopTimeUpdate(String stopName) {
        return new StopTimeUpdateDto(1, 1, "stop-id", null, null, null, null, null, null, null, null, stopName);
    }
}
