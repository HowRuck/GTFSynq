package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import java.util.List;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.client.PageQuery;
import org.example.gtfsynq.ui.client.PagedResult;
import org.junit.jupiter.api.Test;

class LiveUpdatesDataProviderTest {

    private final FakeClient client = new FakeClient();
    private final LiveUpdatesDataProvider provider =
            new LiveUpdatesDataProvider(client, "lastSeenAt", SortDirection.DESCENDING, message -> message);

    @Test
    void loadsTripRowsAndCountsThem() {
        var query = rootQuery();

        assertEquals(2, provider.getChildCount(query));
        assertEquals(2, provider.fetchChildren(query).count());
    }

    @Test
    void loadsStopTimesOnlyWhenATripIsExpanded() {
        var trip =
                (TripUpdateRow) provider.fetchChildren(rootQuery()).findFirst().orElseThrow();

        assertTrue(provider.hasChildren(trip));
        assertEquals(0, client.stopCalls);

        var query = new HierarchicalQuery<LiveUpdateRow, Void>(0, 100, List.of(), null, null, trip);
        var stops = provider.fetchChildren(query).toList();

        assertEquals(2, stops.size());
        assertEquals(1, client.stopCalls);
        assertFalse(provider.hasChildren(stops.getFirst()));
    }

    @Test
    void everyTripIsExpandableEvenWithoutLiveStopRows() {
        var tripWithoutLiveStops = (TripUpdateRow) provider.fetchChildren(rootQuery())
                .filter(row ->
                        row instanceof TripUpdateRow tripRow && tripRow.update().getStopTimeUpdateCount() == 0)
                .findFirst()
                .orElseThrow();

        assertTrue(provider.hasChildren(tripWithoutLiveStops), "the static schedule may still have stops");
    }

    @Test
    void cachesStopTimesUntilTheNextRefresh() {
        var trip =
                (TripUpdateRow) provider.fetchChildren(rootQuery()).findFirst().orElseThrow();
        var query = new HierarchicalQuery<LiveUpdateRow, Void>(0, 100, List.of(), null, null, trip);

        assertEquals(2, provider.getChildCount(query));
        provider.fetchChildren(query).count();
        provider.fetchChildren(query).count();
        assertEquals(1, client.stopCalls);

        provider.invalidate();
        provider.fetchChildren(query).count();
        assertEquals(2, client.stopCalls);
    }

    @Test
    void usesStableIdsAcrossRefreshes() {
        var before = provider.fetchChildren(rootQuery()).toList().getFirst();
        provider.invalidate();
        var after = provider.fetchChildren(rootQuery()).toList().getFirst();

        assertEquals(10L, provider.getId(before));
        assertEquals(provider.getId(before), provider.getId(after));

        var child = provider.fetchChildren(
                        new HierarchicalQuery<LiveUpdateRow, Void>(0, 100, List.of(), null, null, before))
                .findFirst()
                .orElseThrow();
        assertEquals("10:1", provider.getId(child));
    }

    @Test
    void passesTheSearchTermToTheBackend() {
        provider.setSearch("12ab");

        provider.fetchChildren(rootQuery()).count();

        assertEquals("12ab", client.lastQuery.search(), "the backend does the filtering, not the grid");
    }

    private static HierarchicalQuery<LiveUpdateRow, Void> rootQuery() {
        return new HierarchicalQuery<>(0, 25, List.of(), null, null, null);
    }

    /** Serves two trips; only the first one has stop time updates. */
    private static final class FakeClient extends GtfsQueryClient {

        private int stopCalls;
        private PageQuery lastQuery;

        @Override
        public PagedResult<TripUpdate> tripUpdates(PageQuery query) {
            lastQuery = query;
            var items = List.of(
                    TripUpdate.newBuilder()
                            .setTripUpdateId(10)
                            .setFeedId("feed")
                            .setStopTimeUpdateCount(2)
                            .build(),
                    TripUpdate.newBuilder()
                            .setTripUpdateId(20)
                            .setFeedId("feed")
                            .build());
            return new PagedResult<>(items, items.size());
        }

        @Override
        public List<StopTimeUpdate> stopTimeUpdates(long tripUpdateId) {
            stopCalls++;
            return List.of(
                    StopTimeUpdate.newBuilder()
                            .setTripUpdateId(tripUpdateId)
                            .setStopSequence(1)
                            .setStopId("A")
                            .build(),
                    StopTimeUpdate.newBuilder()
                            .setTripUpdateId(tripUpdateId)
                            .setStopSequence(2)
                            .setStopId("B")
                            .build());
        }
    }
}
