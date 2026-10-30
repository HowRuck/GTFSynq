package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripMarker;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.LiveMap;
import org.example.gtfsynq.ui.component.LiveUpdatesGrid;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.TripDetailPanel;

/** Live trip updates, refreshed while the view is attached. */
@Route(value = "live", layout = MainLayout.class)
public class LiveUpdatesView extends VerticalLayout implements HasDynamicTitle {

    private static final int POLL_INTERVAL_MS = 60_000;

    private final GtfsQueryClient client;
    private final LiveUpdatesGrid table;
    private final LiveMap map = new LiveMap();
    private final SplitLayout layout = new SplitLayout(SplitLayout.Orientation.HORIZONTAL);
    private final TripDetailPanel detail = new TripDetailPanel();
    private final Div loadingOverlay;

    @ConfigProperty(name = "gtfsynq.ui.map.transport-overlay-url")
    Optional<String> transportOverlayUrl;

    @ConfigProperty(name = "gtfsynq.ui.map.transport-overlay-opacity", defaultValue = "0.55")
    double transportOverlayOpacity;

    private Registration pollRegistration;
    private boolean attached;
    private boolean markersLoading;
    private boolean markersLoaded;
    private long lastMarkersFingerprint;
    private String lastMapError;
    private TripUpdate selectedTrip;
    private long detailRequest;
    private boolean detailLoaded;
    private String lastDetailError;
    private double detailSplitPosition = 70;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        this.client = client;
        table = new LiveUpdatesGrid(client, "lastSeenAt", SortDirection.DESCENDING);

        // Feed, agency, route and route type are inline header filters inside the grid.
        var refresh = new Button(getTranslation("live.refresh"), event -> {
            table.refresh();
            getUI().ifPresent(ui -> {
                loadMarkers(ui, true);
                loadTripDetail(ui, false);
            });
        });
        refresh.setIcon(VaadinIcon.REFRESH.create());

        map.getElement().setAttribute("aria-label", getTranslation("live.map.aria"));
        map.setReachedTexts(getTranslation("live.map.reached.justNow"), getTranslation("live.map.reached.minutes"));

        var progress = new ProgressBar();
        progress.setIndeterminate(true);
        progress.setWidth("5rem");

        var loadingCard = new HorizontalLayout(progress, new Span(getTranslation("live.map.loading")));
        loadingCard.setPadding(false);
        loadingCard.setSpacing(false);
        loadingCard.setAlignItems(Alignment.CENTER);
        loadingCard.addClassName("live-map-loading-card");

        loadingOverlay = new Div(loadingCard);
        loadingOverlay.addClassName("live-map-loading");
        loadingOverlay.setVisible(false);

        // The overlay is a sibling of the map, so the map keeps ownership of its canvas and the
        // loading card uses regular Vaadin components and theme colours.
        var mapArea = new Div(map, loadingOverlay);
        mapArea.getStyle().set("position", "relative").set("width", "100%").set("height", "100%");
        mapArea.setMinHeight("16rem");

        // The component's default height keeps it usable on its own; the split panel decides the
        // height here, and the map resizes with the splitter.
        map.getElement().getStyle().set("height", "100%");

        table.setSizeFull();
        table.setMinHeight("12rem");
        table.addItemClickListener(event -> openTrip(event.getItem()));

        detail.addCloseListener(event -> closeTrip());
        Shortcuts.addShortcutListener(
                this,
                event -> {
                    if (selectedTrip != null) {
                        closeTrip();
                    }
                },
                Key.ESCAPE);

        var split = new SplitLayout(mapArea, table);
        split.setOrientation(SplitLayout.Orientation.VERTICAL);
        split.setSizeFull();
        split.setSplitterPosition(50);

        // The detail pane sits next to the map and the table, full height. The horizontal splitter
        // between the overview and the detail is draggable, like the one between the map and table.
        layout.addToPrimary(split);
        layout.setSizeFull();
        // No detail content yet, so there is nothing to resize; the class hides the empty splitter.
        layout.addClassName("live-detail-closed");

        setSizeFull();
        var header =
                PageHeader.create(getTranslation("page.live.title"), getTranslation("page.live.subtitle"), refresh);
        setFlexShrink(0, header);
        add(header, layout);
        setFlexGrow(1, layout);
    }

    /**
     * Opens the selected trip in the detail pane and loads its stop timeline and map path.
     *
     * <p>The rows stay a flat table; the pane is what replaces the former tree expansion, and it
     * keeps the map and the other trips visible while it is open.
     */
    private void openTrip(TripUpdate trip) {
        selectedTrip = trip;
        detailLoaded = false;
        lastDetailError = null;
        table.setActiveTrip(trip);
        if (layout.getSecondaryComponent() == null) {
            layout.removeClassName("live-detail-closed");
            layout.addToSecondary(detail);
            layout.setSplitterPosition(detailSplitPosition);
        }
        getUI().ifPresentOrElse(ui -> loadTripDetail(ui, true), () -> detail.showLoading(trip));
    }

    /** Closes the detail pane and cancels any stop times still on the way. */
    private void closeTrip() {
        selectedTrip = null;
        detailLoaded = false;
        detailRequest++;
        table.setActiveTrip(null);
        if (layout.getSecondaryComponent() == detail) {
            var position = layout.getSplitterPosition();
            if (position != null) {
                // Keep the width the user dragged the pane to for the next trip.
                detailSplitPosition = position;
            }
            // Give the overview the full width before dropping the detail, so its stale flex basis
            // cannot leave an empty strip where the pane used to be.
            layout.setSplitterPosition(100);
            layout.remove(detail);
        }
        layout.addClassName("live-detail-closed");
    }

    /**
     * Loads the selected trip's stop times off the UI thread.
     *
     * <p>A poll reloads the timeline too, so an open trip follows the feed without the user
     * reselecting it. Requests are numbered so a slow reply for a previously selected trip cannot
     * overwrite the pane.
     */
    private void loadTripDetail(UI ui, boolean showLoading) {
        var trip = selectedTrip;
        if (trip == null) {
            return;
        }
        var request = ++detailRequest;
        if (showLoading) {
            detail.showLoading(trip);
        }
        CompletableFuture.runAsync(() -> {
            List<StopTimeUpdate> stops;
            DataAccessException failure = null;
            try {
                stops = client.stopTimeUpdates(trip.getTripUpdateId());
            } catch (DataAccessException e) {
                failure = e;
                stops = List.of();
            }

            var loaded = stops;
            var error = failure;
            ui.access(() -> {
                if (request != detailRequest || !attached) {
                    return;
                }
                if (error != null) {
                    var message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    if (showLoading || !detailLoaded) {
                        detail.showError(trip, message);
                    } else {
                        // Keep the last good timeline on a failed poll; a notification is enough.
                        reportDetailError(message);
                    }
                    return;
                }
                lastDetailError = null;
                detailLoaded = true;
                detail.showTrip(trip, loaded, Instant.now());
            });
        });
    }

    /**
     * Loads every marker off the UI thread.
     *
     * <p>The map has its own query rather than following the table: the table shows one page at a
     * time, but the map is an overview of every live trip. A poll that arrives while the previous
     * load is still running is dropped instead of stacking requests.
     *
     * @param showLoading whether to show the map's loading state even after the first snapshot
     */
    private void loadMarkers(UI ui, boolean showLoading) {
        if (markersLoading) {
            return;
        }
        markersLoading = true;
        // Polls must not flicker the overlay; it stays for the initial load and manual refreshes.
        if (showLoading || !markersLoaded) {
            loadingOverlay.setVisible(true);
        }
        CompletableFuture.runAsync(() -> {
            List<TripMarker> markers;
            DataAccessException failure = null;
            try {
                markers = client.tripMarkers();
            } catch (DataAccessException e) {
                failure = e;
                markers = List.of();
            }

            var loaded = markers;
            var error = failure;
            ui.access(() -> {
                markersLoading = false;
                loadingOverlay.setVisible(false);
                if (!attached) {
                    return;
                }
                if (error != null) {
                    // Keep the last good markers on screen rather than blanking the map.
                    report(error);
                    return;
                }
                var fingerprint = fingerprint(loaded);
                if (!markersLoaded || fingerprint != lastMarkersFingerprint) {
                    lastMarkersFingerprint = fingerprint;
                    map.setMarkers(markers(loaded));
                }
                markersLoaded = true;
                lastMapError = null;
            });
        });
    }

    /**
     * One marker per trip that has coordinates for the most advanced stop it has reached.
     *
     * <p>Trips that reached the same stop would otherwise share one exact coordinate and render as
     * one dot at high zoom, even though the cluster counted them separately. Co-located markers are
     * spread over a few metres — a few pixels at street zoom — so the cluster count matches the
     * markers a user can actually see and click.
     */
    List<LiveMap.Marker> markers(List<TripMarker> markers) {
        var byPosition = new LinkedHashMap<Position, List<TripMarker>>();
        for (var marker : markers) {
            if (!Double.isFinite(marker.getLat()) || !Double.isFinite(marker.getLon())) {
                continue;
            }
            byPosition
                    .computeIfAbsent(new Position(marker.getLat(), marker.getLon()), position -> new ArrayList<>())
                    .add(marker);
        }

        var result = new ArrayList<LiveMap.Marker>(markers.size());
        var now = Instant.now();
        for (var group : byPosition.values()) {
            // A stable order keeps each trip in the same slot from one poll to the next.
            group.sort(Comparator.comparing(this::tripKey));
            for (var index = 0; index < group.size(); index++) {
                var update = group.get(index);
                var offset = group.size() == 1 ? Spread.NONE : spread(index, group.size(), update.getLat());
                marker(update, update.getLat() + offset.lat(), update.getLon() + offset.lon(), now)
                        .ifPresent(result::add);
            }
        }
        return result;
    }

    /** Stable identity used to order co-located markers. */
    private String tripKey(TripMarker marker) {
        var trip = marker.hasTripId() ? marker.getTripId() : "";
        var route = marker.hasRouteId() ? marker.getRouteId() : "";
        return trip + '|' + route;
    }

    /**
     * Places the {@code index}-th of {@code count} co-located markers on a small ring. Twelve fit on
     * the first ring; a busy terminal uses further rings instead of one crowded circle.
     */
    private static Spread spread(int index, int count, double latitude) {
        var perRing = 12;
        var ring = index / perRing;
        var slot = index % perRing;
        var ringCount = Math.min(count - ring * perRing, perRing);
        var angle = 2 * Math.PI * slot / ringCount;
        var metres = 6.0 + ring * 5.0;
        var lat = metres * Math.sin(angle) / 111_320.0;
        var lon = metres * Math.cos(angle) / (111_320.0 * Math.cos(Math.toRadians(latitude)));
        return new Spread(lat, lon);
    }

    Optional<LiveMap.Marker> marker(TripMarker update) {
        return marker(update, update.getLat(), update.getLon(), Instant.now());
    }

    private Optional<LiveMap.Marker> marker(TripMarker update, double lat, double lon, Instant now) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return Optional.empty();
        }
        var label = join(route(update), trip(update));
        var detail = join(
                update.hasStopName() ? update.getStopName() : null,
                update.hasDelaySeconds() ? getTranslation("live.map.delay", delay(update.getDelaySeconds())) : null);
        Integer reachedMinutesAgo = null;
        if (update.hasReachedAt()) {
            var reachedAt = Instant.ofEpochSecond(
                    update.getReachedAt().getSeconds(), update.getReachedAt().getNanos());
            reachedMinutesAgo =
                    (int) Math.max(0, Duration.between(reachedAt, now).toMinutes());
        }
        return Optional.of(new LiveMap.Marker(
                lat,
                lon,
                label,
                detail,
                update.hasDelaySeconds() ? update.getDelaySeconds() : null,
                reachedMinutesAgo));
    }

    /** Route part of the label: the route type names the mode, then the short name or id. */
    private String route(TripMarker update) {
        var route = update.hasRouteShortName()
                ? update.getRouteShortName()
                : update.hasRouteId() ? update.getRouteId() : "";
        if (route.isEmpty() || !update.hasRouteType()) {
            return route;
        }
        return Columns.routeTypeLabel(this, update.getRouteType()) + " " + route;
    }

    private static String trip(TripMarker update) {
        if (update.hasTripShortName()) {
            return update.getTripShortName();
        }
        return update.hasTripId() ? update.getTripId() : "";
    }

    private static String join(String first, String second) {
        var left = first == null ? "" : first;
        var right = second == null ? "" : second;
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + " · " + right;
    }

    private static String delay(int seconds) {
        return seconds == 0 ? "0" : String.format("%+d", seconds);
    }

    /**
     * Content fingerprint of the raw snapshot. Protobuf messages hash by value, so an unchanged
     * reply — which the API cache and the store's deduplication make common — skips label building,
     * marker spreading and the multi-megabyte JSON serialisation.
     */
    static long fingerprint(List<TripMarker> markers) {
        var hash = 1L;
        for (var marker : markers) {
            hash = 31 * hash + marker.hashCode();
        }
        return hash;
    }

    private void report(DataAccessException e) {
        // Polling would otherwise repeat the same notification every interval.
        var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!message.equals(lastMapError)) {
            lastMapError = message;
            Notification.show(getTranslation("grid.error.load", message), 5000, Notification.Position.BOTTOM_END);
        }
    }

    private void reportDetailError(String message) {
        if (!message.equals(lastDetailError)) {
            lastDetailError = message;
            Notification.show(getTranslation("grid.error.load", message), 5000, Notification.Position.BOTTOM_END);
        }
    }

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.live.title"));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        attached = true;
        var ui = attachEvent.getUI();
        transportOverlayUrl
                .filter(url -> !url.isBlank())
                .ifPresent(url -> map.setTransportOverlay(url, transportOverlayOpacity));
        ui.setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = ui.addPollListener(event -> {
            table.refresh();
            loadMarkers(ui, false);
            loadTripDetail(ui, false);
        });
        loadMarkers(ui, true);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        attached = false;
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    private record Position(double lat, double lon) {}

    private record Spread(double lat, double lon) {
        private static final Spread NONE = new Spread(0, 0);
    }
}
