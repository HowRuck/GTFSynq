package org.example.gtfsynq.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.example.gtfsynq.api.dto.FeedHealthDto;
import org.example.gtfsynq.api.dto.OverviewDto;
import org.example.gtfsynq.api.dto.PageResponse;
import org.example.gtfsynq.api.dto.StaticFeedDto;
import org.example.gtfsynq.api.dto.StopTimeUpdateDto;
import org.example.gtfsynq.api.dto.TripMarkerDto;
import org.example.gtfsynq.api.dto.TripUpdateDto;
import org.example.gtfsynq.shared.protocol.PollStatus;
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
    void mapsRouteTypeOnTripUpdates() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdate(null, "Trip 1", "10", "Central - North", 3)))
                .getItems(0);

        assertTrue(update.hasRouteType());
        assertEquals(3, update.getRouteType());
    }

    @Test
    void mapsTheLatestStopCoordinatesForTheMap() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdateWithPosition(52.52, 13.405, "Central Station")))
                .getItems(0);

        assertTrue(update.hasLatestStopLat());
        assertTrue(update.hasLatestStopLon());
        assertTrue(update.hasLatestStopName());
        assertEquals(52.52, update.getLatestStopLat());
        assertEquals(13.405, update.getLatestStopLon());
        assertEquals("Central Station", update.getLatestStopName());
    }

    @Test
    void leavesTheLatestStopCoordinatesAbsentWithoutAStaticMatch() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdate(null, null, null)))
                .getItems(0);

        assertFalse(
                update.hasLatestStopLat(),
                "a trip without a static stop has no position, and mapper must not fabricate 0,0");
        assertFalse(update.hasLatestStopLon());
        assertFalse(update.hasLatestStopName());
    }

    @Test
    void mapsTripMarkersForTheMap() {
        var reached = OffsetDateTime.parse("2026-10-26T10:05:00Z");
        var marker = GtfsQueryMapper.tripMarkersReply(List.of(new TripMarkerDto(
                        123, 52.52, 13.405, "10", "Trip 1", "route-id", "trip-id", 3, "Central Station", 120, reached)))
                .getItems(0);

        assertEquals(123, marker.getTripUpdateId());
        assertEquals(52.52, marker.getLat());
        assertEquals(13.405, marker.getLon());
        assertEquals("10", marker.getRouteShortName());
        assertEquals("Trip 1", marker.getTripShortName());
        assertEquals("route-id", marker.getRouteId());
        assertEquals("trip-id", marker.getTripId());
        assertEquals(3, marker.getRouteType());
        assertEquals("Central Station", marker.getStopName());
        assertEquals(120, marker.getDelaySeconds());
        assertTrue(marker.hasReachedAt());
        assertEquals(reached.toEpochSecond(), marker.getReachedAt().getSeconds());
    }

    @Test
    void leavesOptionalTripMarkerFieldsAbsentWithoutAStaticMatch() {
        var marker = GtfsQueryMapper.tripMarkersReply(
                        List.of(new TripMarkerDto(0, 52.52, 13.405, null, null, null, null, null, null, null, null)))
                .getItems(0);

        assertFalse(marker.hasRouteShortName());
        assertFalse(marker.hasTripShortName());
        assertFalse(marker.hasRouteId());
        assertFalse(marker.hasTripId());
        assertFalse(marker.hasRouteType());
        assertFalse(marker.hasStopName());
        assertFalse(marker.hasDelaySeconds());
        assertFalse(marker.hasReachedAt());
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
    void mapsAgencyNameOnTripUpdates() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdate("MTA", "Trip 1", "10", "Central - North")))
                .getItems(0);

        assertTrue(update.hasAgencyName());
        assertEquals("MTA", update.getAgencyName());
    }

    @Test
    void leavesAgencyNameAbsentWhenNoStaticAgencyMatches() {
        var update = GtfsQueryMapper.tripUpdatesReply(page(tripUpdate(null, null, null, null)))
                .getItems(0);

        assertFalse(update.hasAgencyName());
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

    @Test
    void mapsHttpValidatorsOnStaticFeeds() {
        var feed = GtfsQueryMapper.staticFeedsReply(staticFeedPage(List.of())).getItems(0);

        assertEquals("\"storage\"", feed.getObjectEtag());
        assertEquals("\"source\"", feed.getSourceEtag());
        assertEquals("Mon, 12 Oct 2026 10:15:00 GMT", feed.getSourceLastModified());
    }

    @Test
    void mapsStaticAndRealtimeHealthSeparately() {
        var overview = new OverviewDto(
                3,
                0,
                0,
                0,
                0,
                0,
                null,
                new FeedHealthDto(2, 1, 0, 0),
                FeedHealthDto.of(Map.of("healthy", 4L, "error", 2L)));

        var reply = GtfsQueryMapper.toReply(overview);

        assertEquals(2, reply.getStaticHealth().getHealthy());
        assertEquals(1, reply.getStaticHealth().getWarning());
        assertEquals(4, reply.getRealtimeHealth().getHealthy());
        assertEquals(2, reply.getRealtimeHealth().getError());
        assertEquals(0, reply.getRealtimeHealth().getWarning());
    }

    @Test
    void mapsLastTripUpdateOnlyWhenOneIsHeld() {
        var seen = OffsetDateTime.parse("2026-11-13T12:00:00Z");

        var held = GtfsQueryMapper.toReply(
                new OverviewDto(1, 2, 3, 4, 5, 6, seen, FeedHealthDto.of(Map.of()), FeedHealthDto.of(Map.of())));
        var none = GtfsQueryMapper.toReply(
                new OverviewDto(1, 2, 3, 4, 5, 6, null, FeedHealthDto.of(Map.of()), FeedHealthDto.of(Map.of())));

        assertEquals(seen.toEpochSecond(), held.getLastTripUpdateAt().getSeconds());
        assertFalse(none.hasLastTripUpdateAt());
    }

    @Test
    void mapsKindAndPollStatusOnRealtimeFeeds() {
        var feed = new StaticFeedDto(
                "feed",
                "",
                "",
                "",
                "https://example.com/rt.pb",
                null,
                "\"etag\"",
                null,
                10,
                null,
                null,
                List.of(),
                "realtime",
                "NOT_MODIFIED",
                null);
        var item = GtfsQueryMapper.staticFeedsReply(new PageResponse<>(List.of(feed), 0, 25, 1, 1))
                .getItems(0);

        assertEquals("realtime", item.getKind());
        assertEquals(PollStatus.POLL_STATUS_NOT_MODIFIED, item.getPollStatus());
        assertEquals("\"etag\"", item.getSourceEtag());
    }

    private static TripUpdateDto tripUpdateWithPosition(double lat, double lon, String stopName) {
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
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                lat,
                lon,
                stopName,
                null,
                null);
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
                "https://example.com/gtfs.zip",
                "\"storage\"",
                "\"source\"",
                "Mon, 12 Oct 2026 10:15:00 GMT",
                1024,
                OffsetDateTime.parse("2026-10-23T10:00:00Z"),
                OffsetDateTime.parse("2026-10-23T10:01:00Z"),
                agencyNames,
                "static",
                null,
                null);
        return new PageResponse<>(List.of(feed), 0, 25, 1, 1);
    }

    private static TripUpdateDto tripUpdate(String tripShortName, String routeShortName, String routeLongName) {
        return tripUpdate(null, tripShortName, routeShortName, routeLongName);
    }

    private static TripUpdateDto tripUpdate(
            String agencyName, String tripShortName, String routeShortName, String routeLongName) {
        return tripUpdate(agencyName, tripShortName, routeShortName, routeLongName, null);
    }

    private static TripUpdateDto tripUpdate(
            String agencyName, String tripShortName, String routeShortName, String routeLongName, Integer routeType) {
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
                routeLongName,
                agencyName,
                routeType,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void mapsWhetherAStopComesFromRealtimeData() {
        var live = GtfsQueryMapper.stopTimeUpdatesReply(List.of(stopTimeUpdate("Central Station", true)))
                .getItems(0);
        var scheduled = GtfsQueryMapper.stopTimeUpdatesReply(List.of(stopTimeUpdate("Central Station", false)))
                .getItems(0);

        assertTrue(live.getRealtime());
        assertFalse(scheduled.getRealtime(), "rows filled in from the static schedule are not realtime");
    }

    private static StopTimeUpdateDto stopTimeUpdate(String stopName) {
        return stopTimeUpdate(stopName, true);
    }

    private static StopTimeUpdateDto stopTimeUpdate(String stopName, boolean realtime) {
        return new StopTimeUpdateDto(
                1, 1, "stop-id", null, null, null, null, null, null, null, null, stopName, null, null, null, realtime);
    }

    @Test
    void mapsStopCoordinatesForTheTripPath() {
        var update = GtfsQueryMapper.stopTimeUpdatesReply(List.of(new StopTimeUpdateDto(
                        1,
                        1,
                        "stop-id",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "Central Station",
                        52.52,
                        13.405,
                        null,
                        true)))
                .getItems(0);

        assertTrue(update.hasStopLat());
        assertTrue(update.hasStopLon());
        assertEquals(52.52, update.getStopLat());
        assertEquals(13.405, update.getStopLon());
    }
}
