package org.example.gtfsynq.ui.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import java.util.List;
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
    void loadsOnePageOfTripsAndCountsThem() {
        assertEquals(2, provider.size(query(0, 25)));
        assertEquals(2, provider.fetch(query(0, 25)).count());

        assertEquals("lastSeenAt", client.lastQuery.sort());
        assertTrue(client.lastQuery.descending());
        assertEquals(0, client.lastQuery.page());
        assertEquals(25, client.lastQuery.size());
    }

    @Test
    void pagesByTheRequestedOffset() {
        provider.fetch(query(50, 25)).count();

        assertEquals(2, client.lastQuery.page());
        assertEquals(25, client.lastQuery.size());
    }

    @Test
    void usesTheTripUpdateIdAsStableRowIdentityAcrossRefreshes() {
        var before = provider.fetch(query(0, 25)).findFirst().orElseThrow();
        var after = before.toBuilder()
                .setLastSeenAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1))
                .build();

        assertEquals(before.getTripUpdateId(), provider.getId(before));
        assertEquals(provider.getId(before), provider.getId(after), "a poll changes the row, not its identity");
    }

    @Test
    void passesTheSearchTermToTheBackend() {
        provider.setSearch("12ab");

        provider.fetch(query(0, 25)).count();

        assertEquals("12ab", client.lastQuery.search(), "the backend does the filtering, not the grid");
    }

    @Test
    void passesTheAgencyFilterToTheBackend() {
        provider.setAgency("Verkehrsgemeinschaft Osnabrück");

        provider.fetch(query(0, 25)).count();

        assertEquals("Verkehrsgemeinschaft Osnabrück", client.lastQuery.agency());
    }

    @Test
    void passesTheRouteFiltersToTheBackend() {
        provider.setRoute("221");
        provider.setRouteType(3);

        provider.fetch(query(0, 25)).count();

        assertEquals("221", client.lastQuery.route());
        assertEquals(3, client.lastQuery.routeType());
    }

    @Test
    void countsOncePerFilterCombination() {
        provider.size(query(0, 25));
        provider.size(query(25, 25));
        assertEquals(1, client.calls, "the count does not depend on the page offset");

        provider.setRouteType(3);
        provider.size(query(0, 25));
        assertEquals(2, client.calls, "a changed filter needs a fresh count");
    }

    private static Query<TripUpdate, Void> query(int offset, int limit) {
        return new Query<>(offset, limit, List.<QuerySortOrder>of(), null, null);
    }

    private static final class FakeClient extends GtfsQueryClient {

        private int calls;
        private PageQuery lastQuery;

        @Override
        public PagedResult<TripUpdate> tripUpdates(PageQuery query) {
            calls++;
            lastQuery = query;
            var items = List.of(
                    TripUpdate.newBuilder()
                            .setTripUpdateId(10)
                            .setFeedId("feed")
                            .build(),
                    TripUpdate.newBuilder()
                            .setTripUpdateId(20)
                            .setFeedId("feed")
                            .build());
            return new PagedResult<>(items, items.size());
        }
    }
}
