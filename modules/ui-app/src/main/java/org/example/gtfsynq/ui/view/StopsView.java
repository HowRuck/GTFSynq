package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.Stop;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Static GTFS stops and stations. */
@Route(value = "stops", layout = MainLayout.class)
@PageTitle("Stops · GTFSynq")
public class StopsView extends VerticalLayout {

    @Inject
    public StopsView(GtfsQueryClient client) {
        var table = new LazyGrid<Stop>(client::stops, "stopName", SortDirection.ASCENDING, Columns::stops);
        var feed = Filters.feed(client, table::setFeedId);
        var search = Filters.search(table::setSearch);

        add(PageHeader.create("Stops", "Static GTFS stops and stations.", feed, search), table);
    }
}
