package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
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
public class StopsView extends VerticalLayout implements HasDynamicTitle {

    @Inject
    public StopsView(GtfsQueryClient client) {
        var table = new LazyGrid<Stop>(client::stops, "stopName", SortDirection.ASCENDING, Columns::stops);
        var feed = Filters.feed(client, getTranslation("filter.feed.placeholder"), table::setFeedId);
        var search = Filters.search(getTranslation("filter.search.placeholder"), table::setSearch);

        setSizeFull();
        add(
                PageHeader.create(
                        getTranslation("page.stops.title"), getTranslation("page.stops.subtitle"), feed, search),
                table);
        setFlexGrow(1, table);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.stops.title"));
    }
}
