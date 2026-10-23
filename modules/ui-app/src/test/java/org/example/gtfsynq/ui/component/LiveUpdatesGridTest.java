package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.provider.SortDirection;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.junit.jupiter.api.Test;

class LiveUpdatesGridTest {

    @Test
    void buildsTripAndStopColumnsWithTheHierarchyFirst() {
        UI.setCurrent(new UI());
        try {
            var grid = new LiveUpdatesGrid(new GtfsQueryClient(), "lastSeenAt", SortDirection.DESCENDING);

            assertEquals(12, grid.getColumns().size());
            assertEquals("tripId", grid.getColumns().getFirst().getKey());
            assertNotNull(grid.getColumnByKey("lastSeenAt"));
            assertNotNull(grid.getColumnByKey("feedTs"));
            assertTrue(grid.getColumns().stream().allMatch(Grid.Column::isAutoWidth));
        } finally {
            UI.setCurrent(null);
        }
    }
}
