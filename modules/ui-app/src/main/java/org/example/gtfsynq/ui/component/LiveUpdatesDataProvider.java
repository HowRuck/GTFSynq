package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.provider.hierarchy.AbstractHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.client.PageQuery;

/**
 * Lazy hierarchical data provider for the live updates tree.
 *
 * <p>Root rows are paged and sorted by the backend exactly like {@code LazyGrid}; a trip's children
 * are fetched only when the trip is expanded and then cached until the next refresh. Row ids are
 * stable across refreshes, so the tree keeps its expansion when the polling view reloads it.
 */
final class LiveUpdatesDataProvider extends AbstractHierarchicalDataProvider<LiveUpdateRow, Void> {

    private final GtfsQueryClient client;
    private final String defaultSort;
    private final SortDirection defaultDirection;
    private final Function<String, String> errorMessage;

    private final Map<Long, List<LiveUpdateRow>> stopTimesByTrip = new HashMap<>();

    private String feedId;
    private String lastError;
    private RootPage rootPage;
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

    @Override
    public boolean isInMemory() {
        return false;
    }

    @Override
    public HierarchyFormat getHierarchyFormat() {
        return HierarchyFormat.NESTED;
    }

    @Override
    public boolean hasChildren(LiveUpdateRow item) {
        return item instanceof TripUpdateRow trip && trip.update().getStopTimeUpdateCount() > 0;
    }

    @Override
    public int getChildCount(HierarchicalQuery<LiveUpdateRow, Void> query) {
        if (query.getParent() == null) {
            return rootCount(query);
        }
        return childrenOf(query.getParent()).size();
    }

    @Override
    public Stream<LiveUpdateRow> fetchChildren(HierarchicalQuery<LiveUpdateRow, Void> query) {
        if (query.getParent() == null) {
            return rootPage(query).rows().stream().map(LiveUpdateRow.class::cast);
        }
        return childrenOf(query.getParent()).stream().skip(query.getOffset()).limit(query.getLimit());
    }

    /**
     * Stable identity used by the tree to keep a trip expanded while its data is refreshed.
     *
     * <p>Trip rows are keyed by the trip update id instead of the record instance, because the
     * record changes on every refresh (the feed timestamp and last-seen time move).
     */
    @Override
    public Object getId(LiveUpdateRow item) {
        return switch (item) {
            case TripUpdateRow trip -> trip.tripUpdateId();
            case StopTimeUpdateRow stop ->
                stop.tripUpdateId() + ":" + stop.update().getStopSequence();
        };
    }

    /** Applies a feed filter; returns whether it changed. */
    boolean setFeedId(String value) {
        var normalized = value == null || value.isBlank() ? null : value;
        if (Objects.equals(feedId, normalized)) {
            return false;
        }
        feedId = normalized;
        return true;
    }

    /** Drops the cached pages so the next query hits the backend again. */
    void invalidate() {
        stopTimesByTrip.clear();
        rootPage = null;
        countKey = null;
        total = -1;
    }

    /**
     * Returns the row count without loading a page of rows.
     *
     * <p>Vaadin asks for the count with an unbounded query limit, so this uses a one-row page and
     * reads its total instead of transferring a full page.
     */
    private int rootCount(HierarchicalQuery<LiveUpdateRow, Void> query) {
        var key = rootKey(query);
        var keyWithoutPage = new CountKey(key.feedId(), key.sort(), key.descending());
        if (total < 0 || !keyWithoutPage.equals(countKey)) {
            var counted = loadRoot(new RootKey(key.feedId(), key.sort(), key.descending(), 0, 1));
            total = counted.total();
            countKey = keyWithoutPage;
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private RootPage rootPage(HierarchicalQuery<LiveUpdateRow, Void> query) {
        var key = rootKey(query);
        if (rootPage != null && rootPage.key().equals(key)) {
            return rootPage;
        }

        rootPage = loadRoot(key);
        total = rootPage.total();
        countKey = new CountKey(key.feedId(), key.sort(), key.descending());
        return rootPage;
    }

    private RootKey rootKey(HierarchicalQuery<LiveUpdateRow, Void> query) {
        var order = query.getSortOrders().stream().findFirst().orElse(null);
        var sort = order == null ? defaultSort : order.getSorted();
        var descending = order == null
                ? defaultDirection == SortDirection.DESCENDING
                : order.getDirection() == SortDirection.DESCENDING;

        var size = Math.max(query.getLimit(), 1);
        var page = (int) (query.getOffset() / size);
        return new RootKey(feedId, sort, descending, page, size);
    }

    private RootPage loadRoot(RootKey key) {
        try {
            var result = client.tripUpdates(
                    new PageQuery(key.feedId(), "", key.page(), key.size(), key.sort(), key.descending()));
            lastError = null;
            return new RootPage(
                    key, result.items().stream().map(TripUpdateRow::new).toList(), result.total());
        } catch (DataAccessException e) {
            report(e);
            return new RootPage(key, List.of(), 0);
        }
    }

    private List<LiveUpdateRow> childrenOf(LiveUpdateRow parent) {
        if (!(parent instanceof TripUpdateRow trip)) {
            return List.of();
        }
        return stopTimesByTrip.computeIfAbsent(trip.tripUpdateId(), this::loadStopTimes);
    }

    private List<LiveUpdateRow> loadStopTimes(long tripUpdateId) {
        try {
            var rows = client.stopTimeUpdates(tripUpdateId).stream()
                    .map(stop -> (LiveUpdateRow) new StopTimeUpdateRow(tripUpdateId, stop))
                    .toList();
            lastError = null;
            return rows;
        } catch (DataAccessException e) {
            report(e);
            return List.of();
        }
    }

    private void report(DataAccessException e) {
        // Polling views would otherwise repeat the same notification every interval.
        var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!message.equals(lastError)) {
            lastError = message;
            Notification.show(errorMessage.apply(message), 5000, Notification.Position.BOTTOM_END);
        }
    }

    private record RootKey(String feedId, String sort, boolean descending, int page, int size) {}

    private record CountKey(String feedId, String sort, boolean descending) {}

    private record RootPage(RootKey key, List<TripUpdateRow> rows, long total) {}
}
