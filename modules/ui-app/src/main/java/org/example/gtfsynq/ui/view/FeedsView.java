package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** One current static feed revision per feed. */
@Route(value = "feeds", layout = MainLayout.class)
@PageTitle("Static feeds · GTFSynq")
public class FeedsView extends VerticalLayout {

    @Inject
    public FeedsView(GtfsQueryClient client) {
        var table = new LazyGrid<StaticFeed>(
                query -> client.staticFeeds(query.page(), query.size()),
                "loadedAt",
                SortDirection.DESCENDING,
                Columns::feeds);

        add(PageHeader.create("Static feeds", "One current revision per feed, as recorded by the store app."), table);
    }
}
