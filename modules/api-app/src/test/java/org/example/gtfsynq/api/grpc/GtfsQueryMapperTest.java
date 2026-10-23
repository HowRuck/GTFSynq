package org.example.gtfsynq.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.example.gtfsynq.api.dto.PageResponse;
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

    private static PageResponse<TripUpdateDto> page(TripUpdateDto update) {
        return new PageResponse<>(List.of(update), 0, 25, 1, 1);
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
}
