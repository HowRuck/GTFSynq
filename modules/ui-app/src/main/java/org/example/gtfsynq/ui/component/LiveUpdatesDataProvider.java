package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.SortDirection;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.client.PageQuery;

/**
 * Lazy server-side data provider for the live trip table.
 *
 * <p>The grid asks for one page at a time, so only the visible trips are held in memory. Trips are
 * identified by their {@code trip_update_id}, which stays stable across polls even though every
 * field of the row (the feed timestamp, the last-seen time) changes. That lets a poll refresh the
 * rows without dropping the open trip detail panel.
 */
final class LiveUpdatesDataProvider extends AbstractBackEndDataProvider<TripUpdate, Void> {

    private final GtfsQueryClient client;
    private final String defaultSort;
    private final SortDirection defaultDirection;
    private final Function<String, String> errorMessage;

    private String feedId;
    private String search;
    private String agency;
    private String route;
    private Integer routeType;
    private String lastError;
    private CountKey countKey;
    private long total = -1;

    LiveUpdatesDataProvider(
            GtfsQueryClient client,
            String defaultSort,
            SortDirection defaultDirection,
            Function<String, String> errorMessage) {
        this.client = client;
        this.defaultSort = defaultSort;
        this.defaultDirection = defaultDirection;
        this.errorMessage = errorMessage;
    }

    /** Applies a feed filter; returns whether it changed. */
    boolean setFeedId(String value) {
        var normalized = normalize(value);
        if (Objects.equals(feedId, normalized)) {
            return false;
        }
        feedId = normalized;
        return true;
    }

    /** Applies an agency filter; returns whether it changed. */
    boolean setAgency(String value) {
        var normalized = normalize(value);
        if (Objects.equals(agency, normalized)) {
            return false;
        }
        agency = normalized;
        return true;
    }

    /** Applies a route-id filter; returns whether it changed. */
    boolean setRoute(String value) {
        var normalized = normalize(value);
        if (Objects.equals(route, normalized)) {
            return false;
        }
        route = normalized;
        return true;
    }

    /** Applies a GTFS route-type filter; returns whether it changed. */
    boolean setRouteType(Integer value) {
        if (Objects.equals(routeType, value)) {
            return false;
        }
        routeType = value;
        return true;
    }

    /** Applies a search term over entity, trip and route ids; returns whether it changed. */
    boolean setSearch(String value) {
        var term = value == null ? "" : value.trim();
        var normalized = term.length() >= 3 ? term : null;
        if (Objects.equals(search, normalized)) {
            return false;
        }
        search = normalized;
        return true;
    }

    /** Drops the cached count so the next query hits the backend again. */
    void invalidate() {
        countKey = null;
        total = -1;
    }

    @Override
    public Object getId(TripUpdate item) {
        return item.getTripUpdateId();
    }

    @Override
    protected Stream<TripUpdate> fetchFromBackEnd(Query<TripUpdate, Void> query) {
        try {
            var result = client.tripUpdates(pageQuery(query, query.getOffset(), Math.max(query.getLimit(), 1)));
            total = result.total();
            countKey = countKey(query);
            lastError = null;
            return result.items().stream();
        } catch (DataAccessException e) {
            report(e);
            return Stream.empty();
        }
    }

    /**
     * Counts with a one-row page and reads its total, so the grid never transfers a full page just
     * to display the scrollbar.
     */
    @Override
    protected int sizeInBackEnd(Query<TripUpdate, Void> query) {
        var key = countKey(query);
        if (total < 0 || !key.equals(countKey)) {
            try {
                var oneRow = client.tripUpdates(pageQuery(query, 0, 1));
                total = oneRow.total();
                countKey = key;
                lastError = null;
            } catch (DataAccessException e) {
                report(e);
                return 0;
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private PageQuery pageQuery(Query<TripUpdate, Void> query, long offset, int size) {
        var order = query.getSortOrders().stream().findFirst().orElse(null);
        var sort = order == null ? defaultSort : order.getSorted();
        var descending = order == null
                ? defaultDirection == SortDirection.DESCENDING
                : order.getDirection() == SortDirection.DESCENDING;

        return new PageQuery(
                feedId, search, agency, route, routeType, null, null, (int) (offset / size), size, sort, descending);
    }

    private CountKey countKey(Query<TripUpdate, Void> query) {
        var page = pageQuery(query, 0, 1);
        return new CountKey(
                page.feedId(),
                page.search(),
                page.agency(),
                page.route(),
                page.routeType(),
                page.sort(),
                page.descending());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void report(DataAccessException e) {
        // Polling views would otherwise repeat the same notification every interval.
        var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!message.equals(lastError)) {
            lastError = message;
            Notification.show(errorMessage.apply(message), 5000, Notification.Position.BOTTOM_END);
        }
    }

    private record CountKey(
            String feedId,
            String search,
            String agency,
            String route,
            Integer routeType,
            String sort,
            boolean descending) {}
}
