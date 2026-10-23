package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Static GTFS routes. */
@com.vaadin.flow.router.Route(value = "routes", layout = MainLayout.class)
public class RoutesView extends VerticalLayout implements HasDynamicTitle {

    @Inject
    public RoutesView(GtfsQueryClient client) {
        var table = new LazyGrid<Route>(client::routes, "routeId", SortDirection.ASCENDING, Columns::routes);
        var feed = Filters.feed(client, getTranslation("filter.feed.placeholder"), table::setFeedId);
        var search = Filters.search(getTranslation("filter.search.placeholder"), table::setSearch);

        setSizeFull();
        add(
                PageHeader.create(
                        getTranslation("page.routes.title"), getTranslation("page.routes.subtitle"), feed, search),
                table);
        setFlexGrow(1, table);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.routes.title"));
    }
}
