package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;

/**
 * Live trip updates as a flat table.
 *
 * <p>Each row is a live trip; selecting it opens the trip timeline and map in the detail pane
 * instead of expanding the row into a tree. Trips are loaded lazily page by page, and a poll
 * replaces the visible page while keeping the open trip highlighted.
 */
public class LiveUpdatesGrid extends Grid<TripUpdate> {

    private final LiveUpdatesDataProvider provider;
    private String activeTripKey;

    public LiveUpdatesGrid(GtfsQueryClient client, String defaultSort, SortDirection defaultDirection) {
        provider = new LiveUpdatesDataProvider(client, defaultSort, defaultDirection, this::errorMessage);

        setWidthFull();
        setMultiSort(false);
        setSelectionMode(Grid.SelectionMode.NONE);
        addThemeVariants(GridVariant.ROW_STRIPES);
        // Rows open the trip detail pane, so they read as clickable.
        addClassName("live-trips");
        Columns.liveUpdates(this);
        // Size each column to its content, then let the columns share any remaining width.
        // Resizable lets users widen a truncated column by dragging its header edge.
        getColumns().forEach(column -> column.setAutoWidth(true).setFlexGrow(1).setResizable(true));

        // The open trip stays marked while the polling view reloads the page underneath it.
        setPartNameGenerator(
                trip -> activeTripKey != null && activeTripKey.equals(tripKey(trip)) ? "active-trip" : null);

        inlineFilters(client);
        setItems(provider);
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

    /**
     * Marks the trip whose detail pane is open, or clears the mark when {@code null}.
     *
     * <p>Only the part-name generator changes, but the rows already rendered keep their old part
     * name until Vaadin re-renders them, so the provider is refreshed.
     */
    public void setActiveTrip(TripUpdate trip) {
        var key = trip == null ? null : tripKey(trip);
        if (Objects.equals(activeTripKey, key)) {
            return;
        }
        activeTripKey = key;
        getDataProvider().refreshAll();
    }

    /** Reloads the current page, keeping the open trip marked. */
    public void refresh() {
        provider.invalidate();
        provider.refreshAll();
    }

    private String errorMessage(String detail) {
        return getTranslation("grid.error.load", detail);
    }

    private static String tripKey(TripUpdate trip) {
        return trip.getTripUpdateId() + ":" + trip.getFeedId();
    }
}
