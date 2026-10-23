package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.LazyGrid;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.UiComponents;
import org.example.gtfsynq.ui.format.UiFormat;

/** Landing page: row counts and the most recent static feed revisions. */
@Route(value = "", layout = MainLayout.class)
public class OverviewView extends VerticalLayout implements HasDynamicTitle {

    @Inject
    public OverviewView(GtfsQueryClient client) {
        setSizeFull();

        var stats = new HorizontalLayout();
        stats.setPadding(false);
        stats.setWidthFull();
        stats.setWrap(true);
        stats.add(statCards("—", "—", "—", "—", "—", "—"));

        var feeds = new LazyGrid<StaticFeed>(
                query -> client.staticFeeds(query.page(), query.size()),
                "loadedAt",
                SortDirection.DESCENDING,
                Columns::feedSummary);

        var sectionHeader = new HorizontalLayout(
                new H3(getTranslation("overview.section.revisions")),
                new RouterLink(getTranslation("overview.link.allFeeds"), FeedsView.class));
        sectionHeader.setPadding(false);
        sectionHeader.setWidthFull();
        sectionHeader.setAlignItems(Alignment.CENTER);
        sectionHeader.setJustifyContentMode(JustifyContentMode.BETWEEN);

        add(
                PageHeader.create(getTranslation("page.overview.title"), getTranslation("page.overview.subtitle")),
                stats,
                sectionHeader,
                feeds);
        setFlexGrow(1, feeds);

        loadStats(client, stats);
    }

    /**
     * Loads the row counts off the session-lock thread.
     *
     * <p>The overview query scans several tables, so running it in the constructor would hold the
     * Vaadin session lock until the backend answers. That delay also stalls the push handshake,
     * which then trips Vert.x's blocked-thread checker.
     */
    private void loadStats(GtfsQueryClient client, HorizontalLayout stats) {
        var ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            var counts = new String[] {"—", "—", "—", "—", "—", "—"};
            DataAccessException failure = null;
            try {
                var overview = client.overview();
                counts[0] = UiFormat.count(overview.getStaticFeeds());
                counts[1] = UiFormat.count(overview.getRoutes());
                counts[2] = UiFormat.count(overview.getStops());
                counts[3] = UiFormat.count(overview.getTrips());
                counts[4] = UiFormat.count(overview.getLiveTripUpdates());
                counts[5] = UiFormat.count(overview.getLiveStopTimeUpdates());
            } catch (DataAccessException e) {
                failure = e;
            }

            var error = failure;
            ui.access(() -> {
                stats.removeAll();
                stats.add(statCards(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]));
                if (error != null) {
                    Notification.show(
                            getTranslation("overview.error.load", error.getMessage()),
                            5000,
                            Notification.Position.BOTTOM_END);
                }
            });
        });
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.overview.title"));
    }

    private Component[] statCards(
            String staticFeeds,
            String routes,
            String stops,
            String trips,
            String liveTripUpdates,
            String liveStopTimeUpdates) {
        return new Component[] {
            UiComponents.statCard(getTranslation("overview.stats.staticFeeds"), staticFeeds),
            UiComponents.statCard(getTranslation("overview.stats.routes"), routes),
            UiComponents.statCard(getTranslation("overview.stats.stops"), stops),
            UiComponents.statCard(getTranslation("overview.stats.trips"), trips),
            UiComponents.statCard(getTranslation("overview.stats.liveTripUpdates"), liveTripUpdates),
            UiComponents.statCard(getTranslation("overview.stats.liveStopTimeUpdates"), liveStopTimeUpdates)
        };
    }
}
