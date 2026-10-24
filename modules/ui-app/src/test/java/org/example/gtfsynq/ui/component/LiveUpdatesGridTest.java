package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        assertEquals("100", Columns.routeTypeLabel(new Grid<LiveUpdateRow>(), 100));
    }

    @Test
    void buildsTripAndStopColumnsWithTheHierarchyFirst() {
        UI.setCurrent(new UI());
        try {
            var grid = new LiveUpdatesGrid(new GtfsQueryClient(), "lastSeenAt", SortDirection.DESCENDING);

            assertEquals(15, grid.getColumns().size());
            assertEquals("tripId", grid.getColumns().getFirst().getKey());
            assertNotNull(grid.getColumnByKey("lastSeenAt"));
            assertNotNull(grid.getColumnByKey("feedTs"));
            assertNotNull(grid.getColumnByKey("routeType"));
            assertTrue(grid.getColumns().stream().allMatch(Grid.Column::isAutoWidth));
            assertTrue(grid.getColumns().stream().allMatch(Grid.Column::isResizable));
            assertTrue(grid.getColumns().stream().allMatch(column -> column.getFlexGrow() == 1));
            assertTrue(grid.getColumns().stream().allMatch(column -> column.getTextAlign() == ColumnTextAlign.START));

            assertEquals(2, grid.getHeaderRows().size(), "the grouped header sits above the column headers");
            var groups = grid.getHeaderRows().getFirst();
            assertEquals(5, groups.getCells().size(), "one cell per column group");
            assertEquals(
                    grid.getTranslation("column.group.trip"),
                    groups.getCell(grid.getColumnByKey("tripId")).getText());
            assertEquals(
                    grid.getTranslation("column.group.arrival"),
                    groups.getCell(grid.getColumnByKey("arrivalTime")).getText());
            assertEquals(
                    grid.getTranslation("column.group.freshness"),
                    groups.getCell(grid.getColumnByKey("lastSeenAt")).getText());
        } finally {
            UI.setCurrent(null);
        }
    }
}
