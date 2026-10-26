package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
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
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.example.gtfsynq.shared.protocol.query.TripMarker;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Columns;
import org.example.gtfsynq.ui.component.LiveMap;
import org.example.gtfsynq.ui.component.LiveUpdatesGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Live trip updates, refreshed while the view is attached. */
@Route(value = "live", layout = MainLayout.class)
public class LiveUpdatesView extends VerticalLayout implements HasDynamicTitle {

    private static final int POLL_INTERVAL_MS = 60_000;

    private final GtfsQueryClient client;
    private final LiveUpdatesGrid table;
    private final LiveMap map = new LiveMap();
    private final Div loadingOverlay;
    private Registration pollRegistration;
    private boolean attached;
    private boolean markersLoading;
    private boolean markersLoaded;
    private long lastMarkersFingerprint;
    private String lastMapError;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        this.client = client;
        table = new LiveUpdatesGrid(client, "lastSeenAt", SortDirection.DESCENDING);

        // Feed, agency, route and route type are inline header filters inside the grid.
        var refresh = new Button(getTranslation("live.refresh"), event -> {
            table.refresh();
            getUI().ifPresent(ui -> loadMarkers(ui, true));
        });
        refresh.setIcon(VaadinIcon.REFRESH.create());

        map.getElement().setAttribute("aria-label", getTranslation("live.map.aria"));

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
        mapArea.getStyle().set("position", "relative").set("width", "100%");

        setSizeFull();
        add(
                PageHeader.create(getTranslation("page.live.title"), getTranslation("page.live.subtitle"), refresh),
                mapArea,
                table);
        setFlexGrow(0, mapArea);
        setFlexGrow(1, table);
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
        for (var group : byPosition.values()) {
            // A stable order keeps each trip in the same slot from one poll to the next.
            group.sort(Comparator.comparing(this::tripKey));
            for (var index = 0; index < group.size(); index++) {
                var update = group.get(index);
                var offset = group.size() == 1 ? Spread.NONE : spread(index, group.size(), update.getLat());
                marker(update, update.getLat() + offset.lat(), update.getLon() + offset.lon())
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
        return marker(update, update.getLat(), update.getLon());
    }

    private Optional<LiveMap.Marker> marker(TripMarker update, double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return Optional.empty();
        }
        var label = join(route(update), trip(update));
        var detail = join(
                update.hasStopName() ? update.getStopName() : null,
                update.hasDelaySeconds() ? getTranslation("live.map.delay", delay(update.getDelaySeconds())) : null);
        return Optional.of(new LiveMap.Marker(
                lat, lon, label, detail, update.hasDelaySeconds() ? update.getDelaySeconds() : null));
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

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.live.title"));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        attached = true;
        var ui = attachEvent.getUI();
        ui.setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = ui.addPollListener(event -> {
            table.refresh();
            loadMarkers(ui, false);
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
