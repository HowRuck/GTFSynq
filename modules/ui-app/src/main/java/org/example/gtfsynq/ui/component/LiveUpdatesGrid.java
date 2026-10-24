package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.SortDirection;
import java.util.List;
import org.example.gtfsynq.ui.client.GtfsQueryClient;

/**
 * Live trip updates as a tree: each trip expands to its stop time updates.
 *
 * <p>The trip rows are loaded lazily page by page, and a trip's stops are requested only when the
 * trip is expanded. Polling refresh clears the caches but keeps expanded trips open because rows
 * have stable ids.
 */
public class LiveUpdatesGrid extends TreeGrid<LiveUpdateRow> {

    private final LiveUpdatesDataProvider provider;

    public LiveUpdatesGrid(GtfsQueryClient client, String defaultSort, SortDirection defaultDirection) {
        provider = new LiveUpdatesDataProvider(client, defaultSort, defaultDirection, this::errorMessage);

        setWidthFull();
        setMultiSort(false);
        setSelectionMode(Grid.SelectionMode.NONE);
        addThemeVariants(GridVariant.ROW_STRIPES);
        Columns.liveUpdates(this);
        // Size each column to its content, then let the columns share any remaining width.
        // Resizable lets users widen a truncated column by dragging its header edge.
        getColumns().forEach(column -> column.setAutoWidth(true).setFlexGrow(1).setResizable(true));

        setDataProvider(provider);
        getColumns().stream()
                .filter(column -> defaultSort.equals(column.getKey()))
                .findFirst()
                .ifPresent(column -> sort(List.of(new GridSortOrder<>(column, defaultDirection))));
    }

    /** Applies a feed filter. */
    public void setFeedId(String value) {
        if (provider.setFeedId(value)) {
            refresh();
        }
    }

    /** Applies a search term over entity, trip and route ids. */
    public void setSearch(String value) {
        if (provider.setSearch(value)) {
            refresh();
        }
    }

    /** Reloads trips and stops, keeping expanded trips expanded. */
    public void refresh() {
        provider.invalidate();
        provider.refreshAll();
    }

    private String errorMessage(String detail) {
        return getTranslation("grid.error.load", detail);
    }
}
