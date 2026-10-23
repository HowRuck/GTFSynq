package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.inject.Inject;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.PagedGrid;
import org.example.gtfsynq.ui.component.UiComponents;
import org.example.gtfsynq.ui.format.UiFormat;

/** Landing page: row counts and the most recent static feed revisions. */
@Route(value = "", layout = MainLayout.class)
@PageTitle("Overview · GTFSynq")
public class OverviewView extends VerticalLayout {

    @Inject
    public OverviewView(GtfsQueryClient client) {
        addClassName("page");
        setWidthFull();
        setPadding(false);
        setSpacing(true);

        var error = new Div();
        error.addClassName("error-banner");
        error.setVisible(false);

        var stats = new Div();
        stats.addClassName("stat-grid");
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
            error.setText(e.getMessage());
            error.setVisible(true);
            stats.add(statCards("—", "—", "—", "—", "—", "—"));
        }

        var feeds = new PagedGrid<StaticFeed>(
                query -> client.staticFeeds(query.page(), query.size()),
                "loadedAt",
                SortDirection.DESCENDING,
                10,
                Columns::feedSummary);

        var sectionTitle = new H3("Static feed revisions");
        sectionTitle.addClassName("section-title");
        var allFeeds = new RouterLink("All feeds", FeedsView.class);
        var sectionHeader = new HorizontalLayout(sectionTitle, allFeeds);
        sectionHeader.setWidthFull();
        sectionHeader.setPadding(false);
        sectionHeader.setAlignItems(Alignment.CENTER);
        sectionHeader.setJustifyContentMode(JustifyContentMode.BETWEEN);

        add(
                PageHeader.create("Overview", "What is currently loaded in TimescaleDB."),
                error,
                stats,
                sectionHeader,
                feeds);
    }

    private static Component[] statCards(
            String staticFeeds,
            String routes,
            String stops,
            String trips,
            String liveTripUpdates,
            String liveStopTimeUpdates) {
        return new Component[] {
            UiComponents.statCard("Static feeds", staticFeeds, "Current revisions"),
            UiComponents.statCard("Routes", routes, null),
            UiComponents.statCard("Stops", stops, null),
            UiComponents.statCard("Trips", trips, null),
            UiComponents.statCard("Live trip updates", liveTripUpdates, "Hot table"),
            UiComponents.statCard("Live stop time updates", liveStopTimeUpdates, "Hot table")
        };
    }
}
