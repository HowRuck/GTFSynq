package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.provider.SortDirection;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.junit.jupiter.api.Test;

class LiveUpdatesGridTest {

    @Test
    void prefersStaticDisplayNamesAndFallsBackToIds() {
        var named = TripUpdate.newBuilder()
                .setTripId("trip-id")
                .setRouteId("route-id")
                .setTripShortName("Trip 1")
                .setRouteShortName("10")
                .setRouteLongName("Central - North")
                .build();

        assertEquals("Trip 1", Columns.tripName(named));
        assertEquals("Central - North", Columns.routeName(named));
        assertEquals(
                "10", Columns.routeName(named.toBuilder().clearRouteLongName().build()));

        var unnamed = named.toBuilder()
                .clearTripShortName()
                .clearRouteShortName()
                .clearRouteLongName()
                .build();
        assertEquals("trip-id", Columns.tripName(unnamed));
        assertEquals("route-id", Columns.routeName(unnamed));
    }

    @Test
    void prefersStaticStopNameAndFallsBackToStopId() {
        var named = StopTimeUpdate.newBuilder()
                .setStopId("stop-id")
                .setStopName("Central Station")
                .build();

        assertEquals("Central Station", Columns.stopName(named));
        assertEquals(
                "stop-id", Columns.stopName(named.toBuilder().clearStopName().build()));
    }

    @Test
    void fallsBackToTheNumericCodeForExtendedRouteTypes() {
        assertFalse(Filters.ROUTE_TYPES.contains(100));

        assertEquals("100", Columns.routeTypeLabel(new Grid<TripUpdate>(), 100));
    }

    @Test
    void colorsDelaysByHowEarlyOrLateTheyAre() {
        assertEquals("delay-early", Columns.delayPart(-61));
        assertEquals("delay-on-time", Columns.delayPart(0));
        assertEquals("delay-late", Columns.delayPart(61));
        assertEquals("delay-severe", Columns.delayPart(300));
        assertNull(Columns.delayPart(null));
    }

    @Test
    void buildsATripTableWithTheTripColumnFirst() {
        UI.setCurrent(new UI());
        try {
            var grid = new LiveUpdatesGrid(new GtfsQueryClient(), "lastSeenAt", SortDirection.DESCENDING);

            assertEquals(10, grid.getColumns().size());
            assertEquals("tripId", grid.getColumns().getFirst().getKey());
            assertNotNull(grid.getColumnByKey("lastSeenAt"));
            assertNotNull(grid.getColumnByKey("feedTs"));
            assertNotNull(grid.getColumnByKey("routeType"));
            assertTrue(grid.getColumns().stream().allMatch(Grid.Column::isAutoWidth));
            assertTrue(grid.getColumns().stream().allMatch(Grid.Column::isResizable));
            assertTrue(grid.getColumns().stream().allMatch(column -> column.getFlexGrow() == 1));
            assertTrue(grid.getColumns().stream().allMatch(column -> column.getTextAlign() == ColumnTextAlign.START));

            var currentDelay = grid.getColumnByKey("currentDelay").getPartNameGenerator();
            assertEquals(
                    "delay-late",
                    currentDelay.apply(
                            TripUpdate.newBuilder().setCurrentDelay(120).build()));
            assertNull(currentDelay.apply(TripUpdate.getDefaultInstance()));

            assertEquals(3, grid.getHeaderRows().size(), "group, column headers and the inline filter row");
            var filters = grid.getHeaderRows().get(2);
            assertNotNull(filters.getCell(grid.getColumnByKey("feed")).getComponent(), "feed filter");
            assertNotNull(filters.getCell(grid.getColumnByKey("agency")).getComponent(), "agency filter");
            assertNotNull(filters.getCell(grid.getColumnByKey("routeId")).getComponent(), "route filter");
            assertNotNull(filters.getCell(grid.getColumnByKey("routeType")).getComponent(), "route-type filter");

            var groups = grid.getHeaderRows().getFirst();
            assertEquals(3, groups.getCells().size(), "one cell per column group");
            assertEquals(
                    grid.getTranslation("column.group.trip"),
                    groups.getCell(grid.getColumnByKey("tripId")).getText());
            assertEquals(
                    grid.getTranslation("column.group.service"),
                    groups.getCell(grid.getColumnByKey("startDate")).getText());
            assertEquals(
                    grid.getTranslation("column.group.status"),
                    groups.getCell(grid.getColumnByKey("lastSeenAt")).getText());
        } finally {
            UI.setCurrent(null);
        }
    }
}
