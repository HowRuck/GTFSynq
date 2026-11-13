package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Static feeds (current revision) and polled realtime feeds (latest poll). */
@Route(value = "feeds", layout = MainLayout.class)
public class FeedsView extends VerticalLayout implements HasDynamicTitle {

    /** Feed poll outcomes change on the same cadence as the live view. */
    private static final int POLL_INTERVAL_MS = 60_000;

    private final LazyGrid<StaticFeed> table;

    private Registration pollRegistration;

    private String kind;

    @Inject
    public FeedsView(GtfsQueryClient client) {
        table = new LazyGrid<StaticFeed>(
                query -> client.staticFeeds(query.page(), query.size(), true, query.feedId(), query.search(), kind),
                "loadedAt",
                SortDirection.DESCENDING,
                Columns::feeds);
        var feed = Filters.feed(client, getTranslation("filter.feed.placeholder"), table::setFeedId);
        var kindFilter = Filters.kind(
                getTranslation("filter.kind.placeholder"), value -> getTranslation("feed.kind." + value), value -> {
                    kind = value;
                    table.refresh();
                });
        var search = Filters.search(getTranslation("filter.search.placeholder"), table::setSearch);

        setSizeFull();
        add(
                PageHeader.create(
                        getTranslation("page.feeds.title"),
                        getTranslation("page.feeds.subtitle"),
                        feed,
                        kindFilter,
                        search),
                table);
        setFlexGrow(1, table);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        var ui = attachEvent.getUI();
        ui.setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = ui.addPollListener(event -> table.refresh());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.feeds.title"));
    }
}
