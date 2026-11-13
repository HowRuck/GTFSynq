package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.example.gtfsynq.shared.protocol.query.FeedHealthCounts;
import org.example.gtfsynq.shared.protocol.query.OverviewReply;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.overview.FeedHealthTile;
import org.example.gtfsynq.ui.component.overview.StatCard;
import org.example.gtfsynq.ui.format.TripStatus;
import org.example.gtfsynq.ui.format.UiFormat;

/** Landing page: row counts, trip update freshness and one health tile per feed. */
@Route(value = "", layout = MainLayout.class)
public class OverviewView extends VerticalLayout implements HasDynamicTitle {

    /** Counts, freshness and feed health refresh on the same cadence as the Feeds and Live pages. */
    private static final int POLL_INTERVAL_MS = 60_000;

    /** Trip updates younger than this are fresh; older ones are shown as an amber warning. */
    private static final Duration FRESH_WITHIN = Duration.ofSeconds(60);

    private static final Duration SECONDS_LABEL_LIMIT = Duration.ofMinutes(2);

    /** Feeds shown as tiles; a deployment with more feeds than this shows the first page only. */
    private static final int TILE_LIMIT = 100;

    private final GtfsQueryClient client;

    private final StatCard staticFeedsCard = new StatCard(getTranslation("overview.stats.staticFeeds"));

    private final StatCard routesCard = new StatCard(getTranslation("overview.stats.routes"));

    private final StatCard stopsCard = new StatCard(getTranslation("overview.stats.stops"));

    private final StatCard tripsCard = new StatCard(getTranslation("overview.stats.trips"));

    private final StatCard realtimeFeedsCard = new StatCard(getTranslation("overview.stats.realtimeFeeds"));

    private final StatCard liveTripUpdatesCard = new StatCard(getTranslation("overview.stats.liveTripUpdates"));

    private final StatCard liveStopTimeUpdatesCard = new StatCard(getTranslation("overview.stats.liveStopTimeUpdates"));

    private final Badge freshness = new Badge();

    private final Div staticTiles = new Div();

    private final Div realtimeTiles = new Div();

    private Registration pollRegistration;

    @Inject
    public OverviewView(GtfsQueryClient client) {
        this.client = client;
        setSizeFull();

        staticTiles.addClassName("feed-tile-grid");
        realtimeTiles.addClassName("feed-tile-grid");
        freshness.setVisible(false);

        var staticSection = section(
                getTranslation("overview.section.static"),
                statGrid(staticFeedsCard, routesCard, stopsCard, tripsCard),
                healthHeader(getTranslation("overview.section.staticHealth")),
                staticTiles);
        var realtimeSection = section(
                getTranslation("overview.section.realtime"),
                freshness,
                statGrid(realtimeFeedsCard, liveTripUpdatesCard, liveStopTimeUpdatesCard),
                healthHeader(getTranslation("overview.section.realtimeHealth")),
                realtimeTiles);

        add(
                PageHeader.create(getTranslation("page.overview.title"), getTranslation("page.overview.subtitle")),
                staticSection,
                realtimeSection);
    }

    private static Div statGrid(StatCard... cards) {
        var grid = new Div(cards);
        grid.addClassName("stat-grid");
        return grid;
    }

    private static VerticalLayout section(String title, Component... content) {
        var layout = new VerticalLayout(new H2(title));
        layout.add(content);
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.setWidthFull();
        return layout;
    }

    /** Health subheading with a link to the full feed list. */
    private HorizontalLayout healthHeader(String title) {
        var header = new HorizontalLayout(
                new H3(title), new RouterLink(getTranslation("overview.link.allFeeds"), FeedsView.class));
        header.setPadding(false);
        header.setWidthFull();
        header.setAlignItems(Alignment.CENTER);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        return header;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        var ui = attachEvent.getUI();
        loadStats(ui);
        ui.setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = ui.addPollListener(event -> loadStats(ui));
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

    /**
     * Loads the counts and the feed list off the session-lock thread.
     *
     * <p>The overview query scans several tables, so running it on the request thread would hold
     * the Vaadin session lock until the backend answers. That delay also stalls the push handshake,
     * which then trips Vert.x's blocked-thread checker.
     */
    private void loadStats(UI ui) {
        CompletableFuture.runAsync(() -> {
            OverviewReply overview = null;
            List<StaticFeed> feeds = null;
            DataAccessException failure = null;
            try {
                overview = client.overview();
            } catch (DataAccessException e) {
                failure = e;
            }
            try {
                feeds = client.staticFeeds(0, TILE_LIMIT, true, null, null, null, "feedId", "asc")
                        .items();
            } catch (DataAccessException e) {
                failure = e;
            }

            var loadedOverview = overview;
            var loadedFeeds = feeds;
            var error = failure;
            ui.access(() -> {
                if (loadedOverview != null) {
                    showCounts(loadedOverview);
                }
                if (loadedFeeds != null) {
                    showTiles(loadedFeeds);
                }
                if (error != null) {
                    Notification.show(
                            getTranslation("overview.error.load", error.getMessage()),
                            5000,
                            Notification.Position.BOTTOM_END);
                }
            });
        });
    }

    private void showCounts(OverviewReply overview) {
        staticFeedsCard.show(overview.getStaticFeeds(), UiFormat.count(overview.getStaticFeeds()));
        staticFeedsCard.showDetail(healthText(overview.getStaticHealth()));
        var realtimeFeeds = totalFeeds(overview.getRealtimeHealth());
        realtimeFeedsCard.show(realtimeFeeds, UiFormat.count(realtimeFeeds));
        realtimeFeedsCard.showDetail(healthText(overview.getRealtimeHealth()));
        routesCard.show(overview.getRoutes(), UiFormat.count(overview.getRoutes()));
        stopsCard.show(overview.getStops(), UiFormat.count(overview.getStops()));
        tripsCard.show(overview.getTrips(), UiFormat.count(overview.getTrips()));
        liveTripUpdatesCard.show(overview.getLiveTripUpdates(), UiFormat.count(overview.getLiveTripUpdates()));
        liveStopTimeUpdatesCard.show(
                overview.getLiveStopTimeUpdates(), UiFormat.count(overview.getLiveStopTimeUpdates()));
        showFreshness(overview.hasLastTripUpdateAt() ? UiFormat.instant(overview.getLastTripUpdateAt()) : null);
    }

    private static long totalFeeds(FeedHealthCounts health) {
        return health.getHealthy() + health.getWarning() + health.getError() + health.getUnknown();
    }

    /** "12 healthy · 1 warning · 0 error", with the feeds never polled appended when there are any. */
    private String healthText(FeedHealthCounts health) {
        var text =
                getTranslation("overview.health.summary", health.getHealthy(), health.getWarning(), health.getError());
        return health.getUnknown() > 0
                ? text + " · " + getTranslation("overview.health.unknown", health.getUnknown())
                : text;
    }

    /** Replaces the tiles; each tile is built here, on the UI thread, so its translations resolve. */
    private void showTiles(List<StaticFeed> feeds) {
        var now = Instant.now();
        staticTiles.removeAll();
        realtimeTiles.removeAll();
        feeds.forEach(feed -> (isRealtime(feed) ? realtimeTiles : staticTiles).add(new FeedHealthTile(feed, now)));
    }

    private static boolean isRealtime(StaticFeed feed) {
        return "realtime".equals(feed.getKind());
    }

    /** Shows how long ago the newest trip update arrived, coloured by how stale it is. */
    private void showFreshness(Instant lastTripUpdate) {
        freshness.setVisible(true);
        freshness.removeThemeVariants(BadgeVariant.SUCCESS, BadgeVariant.WARNING, BadgeVariant.ERROR);
        if (lastTripUpdate == null) {
            freshness.setText(getTranslation("overview.freshness.none"));
            freshness.addThemeVariants(BadgeVariant.ERROR);
            return;
        }

        var age = Duration.between(lastTripUpdate, Instant.now());
        if (age.compareTo(SECONDS_LABEL_LIMIT) < 0) {
            freshness.setText(getTranslation("overview.freshness.seconds", Math.max(0, age.toSeconds())));
        } else {
            freshness.setText(getTranslation("overview.freshness.minutes", age.toMinutes()));
        }
        if (age.compareTo(FRESH_WITHIN) < 0) {
            freshness.addThemeVariants(BadgeVariant.SUCCESS);
        } else if (age.compareTo(TripStatus.STALE_AFTER) < 0) {
            freshness.addThemeVariants(BadgeVariant.WARNING);
        } else {
            freshness.addThemeVariants(BadgeVariant.ERROR);
        }
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.overview.title"));
    }
}
