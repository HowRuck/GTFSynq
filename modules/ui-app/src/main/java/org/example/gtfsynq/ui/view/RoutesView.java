package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.PagedGrid;

/** Static GTFS routes. */
@com.vaadin.flow.router.Route(value = "routes", layout = MainLayout.class)
@PageTitle("Routes · GTFSynq")
public class RoutesView extends VerticalLayout {

    @Inject
    public RoutesView(GtfsQueryClient client) {
        addClassName("page");
        setWidthFull();
        setPadding(false);
        setSpacing(true);

        var table = new PagedGrid<Route>(client::routes, "routeId", SortDirection.ASCENDING, 25, Columns::routes);
        var feed = Filters.feed(client, table::setFeedId);
        var search = Filters.search(table::setSearch);

        add(PageHeader.create("Routes", "Static GTFS routes, one current revision per feed.", feed, search), table);
    }
}
