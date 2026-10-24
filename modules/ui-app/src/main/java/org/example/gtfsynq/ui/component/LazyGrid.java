package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.SortDirection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.PageLoader;
import org.example.gtfsynq.ui.client.PageQuery;

/**
 * A server-backed grid that loads rows lazily through Vaadin's callback data provider.
 *
 * <p>The grid asks the backend for a window of rows as the user scrolls or changes sorting, so only
 * the visible slice is ever held in memory. Totals are not needed for this; the grid stops asking
 * when the backend returns an empty page.
 */
public class LazyGrid<T> extends Grid<T> {

    private static final int MIN_SEARCH_LENGTH = 3;

    private final PageLoader<T> loader;
    private final String defaultSort;
    private final SortDirection defaultDirection;

    private String feedId;
    private String search;
    private Integer routeType;
    private Integer directionId;
    private Integer locationType;
    private String lastError;

    public LazyGrid(
            PageLoader<T> loader,
            String defaultSort,
            SortDirection defaultDirection,
            Consumer<Grid<T>> columnConfigurer) {
        this.loader = loader;
        this.defaultSort = defaultSort;
        this.defaultDirection = defaultDirection;

        setWidthFull();
        setMultiSort(false);
        setSelectionMode(Grid.SelectionMode.NONE);
        addThemeVariants(GridVariant.ROW_STRIPES);
        columnConfigurer.accept(this);
        // Size each column to its content, then let the columns share any remaining width.
        // Resizable lets users widen a truncated column by dragging its header edge.
        getColumns().forEach(column -> column.setAutoWidth(true).setFlexGrow(1).setResizable(true));

        setItems(this::fetch);
        getColumns().stream()
                .filter(column -> Objects.equals(defaultSort, column.getKey()))
                .findFirst()
                .ifPresent(column -> sort(List.of(new GridSortOrder<>(column, defaultDirection))));
    }

    /** Applies a feed filter. */
    public void setFeedId(String value) {
        var normalized = value == null || value.isBlank() ? null : value;
        if (Objects.equals(feedId, normalized)) {
            return;
        }
        feedId = normalized;
        refresh();
    }

    /** Applies a search term; shorter terms are ignored, matching the REST API. */
    public void setSearch(String value) {
        var term = value == null ? "" : value.trim();
        var normalized = term.length() >= MIN_SEARCH_LENGTH ? term : null;
        if (Objects.equals(search, normalized)) {
            return;
        }
        search = normalized;
        refresh();
    }

    /** Filters routes by GTFS route type; {@code null} means all types. */
    public void setRouteType(Integer value) {
        if (Objects.equals(routeType, value)) {
            return;
        }
        routeType = value;
        refresh();
    }

    /** Filters trips by GTFS direction id; {@code null} means both directions. */
    public void setDirectionId(Integer value) {
        if (Objects.equals(directionId, value)) {
            return;
        }
        directionId = value;
        refresh();
    }

    /** Filters stops by GTFS location type; {@code null} means all types. */
    public void setLocationType(Integer value) {
        if (Objects.equals(locationType, value)) {
            return;
        }
        locationType = value;
        refresh();
    }

    /** Reloads the grid from the backend. */
    public void refresh() {
        getDataProvider().refreshAll();
    }

    private Stream<T> fetch(Query<T, Void> query) {
        var order = query.getSortOrders().stream().findFirst().orElse(null);
        var sortKey = order == null ? defaultSort : order.getSorted();
        var descending = order == null
                ? defaultDirection == SortDirection.DESCENDING
                : order.getDirection() == SortDirection.DESCENDING;

        var size = Math.max(query.getLimit(), 1);
        var page = (int) (query.getOffset() / size);

        try {
            var items = loader.load(new PageQuery(
                            feedId, search, routeType, directionId, locationType, page, size, sortKey, descending))
                    .items();
            lastError = null;
            return items.stream();
        } catch (DataAccessException e) {
            // Polling views would otherwise repeat the same notification every interval.
            var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!message.equals(lastError)) {
                lastError = message;
                Notification.show(getTranslation("grid.error.load", message), 5000, Notification.Position.BOTTOM_END);
            }
            return Stream.empty();
        }
    }
}
