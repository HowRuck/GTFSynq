package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.provider.SortDirection;
import java.util.List;
import java.util.Set;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.client.PagedResult;
import org.junit.jupiter.api.Test;

/**
 * Guards the one sizing contract every console table shares: the same theme and the same column
 * sizing. Row height and font size are not asserted here because the tables never set them; leaving
 * them to Aura is what keeps the tables identical.
 */
class ConsoleGridTest {

    @Test
    void everyTableSharesOneSizingContract() {
        UI.setCurrent(new UI());
        try {
            assertConsoleSizing(lazyGrid());
            assertConsoleSizing(new LiveUpdatesGrid(new GtfsQueryClient(), "lastSeenAt", SortDirection.DESCENDING));
        } finally {
            UI.setCurrent(null);
        }
    }

    private static void assertConsoleSizing(Grid<?> grid) {
        assertEquals(Set.of("row-stripes"), grid.getThemeNames(), "every table uses the same theme");

        var dataColumns = grid.getColumns().stream()
                .filter(column -> !"open".equals(column.getKey()))
                .toList();
        assertTrue(dataColumns.stream().allMatch(Grid.Column::isAutoWidth));
        assertTrue(dataColumns.stream().allMatch(Grid.Column::isResizable));
        assertTrue(dataColumns.stream().allMatch(column -> column.getFlexGrow() == 1));
    }

    private static LazyGrid<String> lazyGrid() {
        return new LazyGrid<>(
                query -> new PagedResult<String>(List.of(), 0),
                "value",
                SortDirection.ASCENDING,
                grid -> grid.addColumn(value -> value).setKey("value").setSortable(true));
    }
}
