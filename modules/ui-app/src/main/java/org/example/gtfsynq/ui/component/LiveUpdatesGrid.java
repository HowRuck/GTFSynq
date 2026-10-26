package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.client.DataAccessException;
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

        inlineFilters(client);
        setDataProvider(provider);
        getColumns().stream()
                .filter(column -> defaultSort.equals(column.getKey()))
                .findFirst()
                .ifPresent(column -> sort(List.of(new GridSortOrder<>(column, defaultDirection))));
    }

    /**
     * Registers a listener for every page of trip rows the grid loads.
     *
     * <p>The map uses this to stay on the same rows as the table: same filters, same page, same
     * refresh cadence, without issuing a second query.
     *
     * @param listener receives the trip rows of a freshly loaded page
     */
    public void onTripsLoaded(Consumer<List<TripUpdate>> listener) {
        provider.onRootPageLoaded(
                rows -> listener.accept(rows.stream().map(TripUpdateRow::update).toList()));
    }

    /** Applies a feed filter. */
    public void setFeedId(String value) {
        if (provider.setFeedId(value)) {
            refresh();
        }
    }

    /** Applies an agency filter. */
    public void setAgency(String value) {
        if (provider.setAgency(value)) {
            refresh();
        }
    }

    /** Applies a route-id filter. */
    public void setRoute(String value) {
        if (provider.setRoute(value)) {
            refresh();
        }
    }

    /** Applies a GTFS route-type filter. */
    public void setRouteType(Integer value) {
        if (provider.setRouteType(value)) {
            refresh();
        }
    }

    /**
     * Adds a filter row inside the grid header: feed and agency selectors, a route-id field and a
     * route-type selector, all applied server-side by the data provider.
     */
    private void inlineFilters(GtfsQueryClient client) {
        var filters = appendHeaderRow();
        filters.getCell(getColumnByKey("feed")).setComponent(feedFilter(client));
        filters.getCell(getColumnByKey("agency")).setComponent(agencyFilter(client));
        filters.getCell(getColumnByKey("routeId")).setComponent(routeFilter());
        filters.getCell(getColumnByKey("routeType")).setComponent(routeTypeFilter());
    }

    private ComboBox<String> feedFilter(GtfsQueryClient client) {
        var feed = select(getTranslation("filter.feed.placeholder"), this::setFeedId);
        loadAsync(client::feedIds, feed);
        return feed;
    }

    private ComboBox<String> agencyFilter(GtfsQueryClient client) {
        var agency = select(getTranslation("filter.agency.placeholder"), this::setAgency);
        loadAsync(() -> client.agencyNames(null), agency);
        return agency;
    }

    private ComboBox<Integer> routeTypeFilter() {
        var routeType = select(getTranslation("filter.routeType.placeholder"), this::setRouteType);
        routeType.setItems(Filters.ROUTE_TYPES);
        routeType.setItemLabelGenerator(type -> getTranslation("filter.routeType." + type));
        return routeType;
    }

    private TextField routeFilter() {
        var route = new TextField();
        route.setPlaceholder(getTranslation("filter.route.placeholder"));
        route.setClearButtonVisible(true);
        route.setWidthFull();
        route.addThemeVariants(TextFieldVariant.SMALL);
        route.setValueChangeMode(ValueChangeMode.LAZY);
        route.setValueChangeTimeout(250);
        route.addValueChangeListener(event -> setRoute(event.getValue()));
        return route;
    }

    private <T> ComboBox<T> select(String placeholder, java.util.function.Consumer<T> onChange) {
        var select = new ComboBox<T>();
        select.setPlaceholder(placeholder);
        select.setClearButtonVisible(true);
        select.setAllowCustomValue(false);
        select.setWidthFull();
        select.addThemeName("small");
        select.addValueChangeListener(event -> onChange.accept(event.getValue()));
        return select;
    }

    private <T> void loadAsync(Supplier<List<T>> loader, ComboBox<T> target) {
        var ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                var items = loader.get();
                ui.access(() -> target.setItems(items));
            } catch (DataAccessException ignored) {
                // The grid shows the gRPC failure; an empty selector is enough here.
            }
        });
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
