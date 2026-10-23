package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.Trip;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.PagedGrid;

/** Static GTFS trips and their routes and services. */
@Route(value = "trips", layout = MainLayout.class)
@PageTitle("Trips · GTFSynq")
public class TripsView extends VerticalLayout {

    @Inject
    public TripsView(GtfsQueryClient client) {
        addClassName("page");
        setWidthFull();
        setPadding(false);
        setSpacing(true);

        var table = new PagedGrid<Trip>(client::trips, "tripId", SortDirection.ASCENDING, 25, Columns::trips);
        var feed = Filters.feed(client, table::setFeedId);
        var search = Filters.search(table::setSearch);

        add(PageHeader.create("Trips", "Static GTFS trips and their routes and services.", feed, search), table);
    }
}
