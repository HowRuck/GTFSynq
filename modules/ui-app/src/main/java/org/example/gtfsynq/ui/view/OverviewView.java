package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.Component;
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
        try {
            var overview = client.overview();
            stats.add(statCards(
                    UiFormat.count(overview.getStaticFeeds()),
                    UiFormat.count(overview.getRoutes()),
                    UiFormat.count(overview.getStops()),
                    UiFormat.count(overview.getTrips()),
                    UiFormat.count(overview.getLiveTripUpdates()),
                    UiFormat.count(overview.getLiveStopTimeUpdates())));
        } catch (DataAccessException e) {
            Notification.show(
                    getTranslation("overview.error.load", e.getMessage()), 5000, Notification.Position.BOTTOM_END);
            stats.add(statCards("—", "—", "—", "—", "—", "—"));
        }

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
