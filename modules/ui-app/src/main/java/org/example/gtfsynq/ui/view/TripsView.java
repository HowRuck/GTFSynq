package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.Trip;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Static GTFS trips and their routes and services. */
@Route(value = "trips", layout = MainLayout.class)
public class TripsView extends VerticalLayout implements HasDynamicTitle {

    @Inject
    public TripsView(GtfsQueryClient client) {
        var table = new LazyGrid<Trip>(client::trips, "tripId", SortDirection.ASCENDING, Columns::trips);
        var feed = Filters.feed(client, getTranslation("filter.feed.placeholder"), table::setFeedId);
        var search = Filters.search(getTranslation("filter.search.placeholder"), table::setSearch);

        add(
                PageHeader.create(
                        getTranslation("page.trips.title"), getTranslation("page.trips.subtitle"), feed, search),
                table);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.trips.title"));
    }
}
