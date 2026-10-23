package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.provider.SortDirection;
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
    void buildsTripAndStopColumnsWithTheHierarchyFirst() {
        UI.setCurrent(new UI());
        try {
            var grid = new LiveUpdatesGrid(new GtfsQueryClient(), "lastSeenAt", SortDirection.DESCENDING);

            assertEquals(13, grid.getColumns().size());
            assertEquals("tripId", grid.getColumns().getFirst().getKey());
            assertNotNull(grid.getColumnByKey("lastSeenAt"));
            assertNotNull(grid.getColumnByKey("feedTs"));
            assertTrue(grid.getColumns().stream().allMatch(Grid.Column::isAutoWidth));
            assertTrue(grid.getColumns().stream().allMatch(column -> column.getFlexGrow() == 1));
        } finally {
            UI.setCurrent(null);
        }
    }
}
