package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.Shortcuts;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
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
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripMarker;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.Filters;
import org.example.gtfsynq.ui.component.LiveMap;
import org.example.gtfsynq.ui.component.LiveUpdatesGrid;
import org.example.gtfsynq.ui.component.PageHeader;
import org.example.gtfsynq.ui.component.TripDetailPanel;

/** Live trip updates, refreshed while the view is attached. */
@Route(value = "live", layout = MainLayout.class)
public class LiveUpdatesView extends VerticalLayout implements HasDynamicTitle {

    private static final int POLL_INTERVAL_MS = 60_000;

    /**
     * Runs the blocking backend loads. One virtual thread per load keeps a slow query from starving
     * the others - and the other sessions - which the common ForkJoinPool does once a few
     * multi-second gRPC calls are in flight.
     */
    /** Markers older than this are too out of date to trust, so the map is locked until a refresh. */
    static final Duration MAX_MARKER_AGE = Duration.ofMinutes(15);

    private static final ExecutorService BACKGROUND_LOADS = Executors.newVirtualThreadPerTaskExecutor();

    private final GtfsQueryClient client;
    private final LiveUpdatesGrid table;
    private final LiveMap map = new LiveMap();
    private final SplitLayout layout = new SplitLayout(SplitLayout.Orientation.HORIZONTAL);
    private final TripDetailPanel detail = new TripDetailPanel();
    private final Div loadingOverlay;
    private final Div staleOverlay;

    // Optional is the MicroProfile Config way to read a property that may be absent: an empty
    // value in application.yaml counts as absent for a plain String injection.
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @ConfigProperty(name = "gtfsynq.ui.map.transport-overlay-url")
    Optional<String> transportOverlayUrl;

    @ConfigProperty(name = "gtfsynq.ui.map.transport-overlay-opacity", defaultValue = "0.55")
    double transportOverlayOpacity;

    private Registration pollRegistration;
    private boolean attached;
    // Written by the watchdog's fallback path as well as the UI thread; a stuck load must never
    // leave the guard set and block every later poll.
    private volatile boolean markersLoading;
    private long markerRequest;
    private boolean markersLoaded;
    private long lastMarkersFingerprint;
    // When the markers on screen were computed by the API; null until a snapshot has been loaded.
    private Instant markersComputedAt;
    private String lastMapError;
    private TripUpdate selectedTrip;
    private long tripLookupRequest;
    private long detailRequest;
    private boolean detailLoaded;
    private String lastDetailError;
    private double detailSplitPosition = 70;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        this.client = client;
        table = new LiveUpdatesGrid(client, "lastSeenAt", SortDirection.DESCENDING);

        // Feed, agency, route and route type are inline header filters inside the grid.
        var refresh = new Button(getTranslation("live.refresh"), event -> refreshAll());
        refresh.setIcon(VaadinIcon.REFRESH.create());

        map.getElement().setAttribute("aria-label", getTranslation("live.map.aria"));
        map.setReachedTexts(getTranslation("live.map.reached.justNow"), getTranslation("live.map.reached.minutes"));
        map.setOpenTripText(getTranslation("live.map.openTrip"));
        map.addTripOpenListener(this::openTripFromMap);

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

        var staleCard = new Card();
        staleCard.setTitle(getTranslation("live.map.stale.title"));
        staleCard.setSubtitle(getTranslation("live.map.stale.text", (int) MAX_MARKER_AGE.toMinutes()));
        staleCard.setHeaderPrefix(VaadinIcon.CLOCK.create());
        var staleRefresh =
                new Button(getTranslation("live.refresh"), VaadinIcon.REFRESH.create(), event -> refreshAll());
        staleRefresh.addThemeVariants(ButtonVariant.PRIMARY);
        staleCard.addToFooter(staleRefresh);
        staleCard.addClassName("live-map-stale-card");
        staleCard.getElement().setAttribute("role", "alert");

        // A blurred, interaction-blocking screen: the map stays visible but unusable while its
        // markers are too old, and the poll keeps retrying in the background to lift it.
        staleOverlay = new Div(staleCard);
        staleOverlay.addClassName("live-map-stale");
        staleOverlay.setVisible(false);

        // The overlays are siblings of the map, so the map keeps ownership of its canvas and the
        // cards use regular Vaadin components and theme colours.
        var mapArea = new Div(map, loadingOverlay, staleOverlay);
        mapArea.getStyle().set("position", "relative").set("width", "100%").set("height", "100%");
        mapArea.setMinHeight("16rem");

        // The component's default height keeps it usable on its own; the split panel decides the
        // height here, and the map resizes with the splitter.
        map.getElement().getStyle().set("height", "100%");

        table.setSizeFull();
        table.setMinHeight("12rem");
        table.addItemClickListener(event -> openTrip(event.getItem()));
        table.setOpenTripHandler(this::openTrip);

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

    /** Reloads the table, the markers and the open trip; shared by both refresh buttons. */
    private void refreshAll() {
        table.refresh();
        getUI().ifPresent(ui -> {
            loadMarkers(ui, true);
            loadTripDetail(ui, false);
        });
    }

    /**
     * Opens the selected trip in the detail pane and loads its stop timeline and map path.
     *
     * <p>The rows stay a flat table; the pane is what replaces the former tree expansion, and it
     * keeps the map and the other trips visible while it is open.
     */
    private void openTrip(TripUpdate trip) {
        // A table row wins over a map lookup that is still on the way.
        tripLookupRequest++;
        showTrip(trip);
    }

    /**
     * Opens a trip selected from a map popup.
     *
     * <p>The popup only carries the update id, so the pane opens at once with its loading state
     * while the full row is fetched; a lookup superseded by another selection is discarded.
     */
    void openTripFromMap(long tripUpdateId) {
        var request = ++tripLookupRequest;
        // The header needs the full row, but the id is enough to open the pane immediately.
        var provisional = TripUpdate.newBuilder().setTripUpdateId(tripUpdateId).build();
        showLoading(provisional);

        getUI().ifPresent(ui -> CompletableFuture.runAsync(
                () -> {
                    TripUpdate trip;
                    try {
                        trip = client.tripUpdate(tripUpdateId);
                    } catch (DataAccessException e) {
                        ui.access(() -> {
                            if (request == tripLookupRequest && attached) {
                                var message =
                                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                                detail.showError(provisional, message);
                            }
                        });
                        return;
                    }
                    ui.access(() -> {
                        if (request == tripLookupRequest && attached) {
                            showTrip(trip);
                        }
                    });
                },
                BACKGROUND_LOADS));
    }

    void showTrip(TripUpdate trip) {
        showLoading(trip);
        getUI().ifPresent(ui -> loadTripDetail(ui, false));
    }

    /** Puts the pane in place and shows its loading state, before any data is fetched. */
    private void showLoading(TripUpdate trip) {
        selectedTrip = trip;
        detailLoaded = false;
        lastDetailError = null;
        if (layout.getSecondaryComponent() == null) {
            layout.removeClassName("live-detail-closed");
            layout.addToSecondary(detail);
            layout.setSplitterPosition(detailSplitPosition);
        }
        detail.showLoading(trip);
        table.setActiveTrip(trip);
    }

    /** Closes the detail pane and cancels any stop times still on the way. */
    private void closeTrip() {
        selectedTrip = null;
        detailLoaded = false;
        tripLookupRequest++;
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
        CompletableFuture.runAsync(
                () -> {
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
                            var message = error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage();
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
                },
                BACKGROUND_LOADS);
    }

    /**
     * Loads every marker off the UI thread.
     *
     * <p>The map has its own query rather than following the table: the table shows one page at a
     * time, but the map is an overview of every live trip. A poll that arrives while the previous
     * load is still running is dropped instead of stacking requests.
     *
     * <p>A load is bounded twice: by the client deadline on the gRPC call, and by a watchdog that
     * releases the loading state if the call never reports back (a queued UI access, a lost worker).
     * Without the watchdog a single stuck load would keep the map in its loading state and block
     * every later poll, because the in-flight guard would never clear.
     *
     * @param showLoading whether to show the map's loading state even after the first snapshot
     */
    private void loadMarkers(UI ui, boolean showLoading) {
        if (markersLoading) {
            return;
        }
        markersLoading = true;
        var request = ++markerRequest;
        // Polls must not flicker the overlay; it stays for the initial load and manual refreshes.
        if (showLoading || !markersLoaded) {
            loadingOverlay.setVisible(true);
        }
        // Translations need the UI thread, so they are captured up front: the background task can
        // then fingerprint, build and serialise the whole payload without touching the UI.
        var texts = markerTexts();

        CompletableFuture.runAsync(
                        () -> {
                            List<TripMarker> markers;
                            Instant computedAt = Instant.now();
                            DataAccessException failure = null;
                            try {
                                var reply = client.tripMarkers();
                                markers = reply.getItemsList();
                                if (reply.hasComputedAt()) {
                                    computedAt = Instant.ofEpochSecond(
                                            reply.getComputedAt().getSeconds(),
                                            reply.getComputedAt().getNanos());
                                }
                            } catch (DataAccessException e) {
                                failure = e;
                                markers = List.of();
                            } catch (RuntimeException e) {
                                failure = new DataAccessException(e.getMessage(), e);
                                markers = List.of();
                            }

                            var loaded = markers;
                            var error = failure;
                            // At tens of thousands of trips the payload is megabytes; building it
                            // here keeps the UI thread free for the rest of the session.
                            var fingerprint = error == null ? fingerprint(loaded) : 0L;
                            var json = error == null ? LiveMap.json(markers(loaded, texts)) : null;
                            var snapshotAt = computedAt;
                            ui.access(() -> finishMarkers(request, fingerprint, json, snapshotAt, error));
                        },
                        BACKGROUND_LOADS)
                .orTimeout(client.markerDeadlineSeconds() + 5, TimeUnit.SECONDS)
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        return;
                    }
                    try {
                        ui.access(() -> abandonMarkers(request));
                    } catch (RuntimeException e) {
                        // The session is gone; only the in-flight guard still needs releasing.
                        markersLoading = false;
                    }
                });
    }

    private void finishMarkers(
            long request, long fingerprint, String markersJson, Instant computedAt, DataAccessException error) {
        if (request != markerRequest) {
            return;
        }
        markersLoading = false;
        loadingOverlay.setVisible(false);
        if (!attached) {
            return;
        }
        if (error != null) {
            // Keep the last good markers on screen rather than blanking the map, but lock it once
            // they have aged out.
            updateStaleLock();
            report(error);
            return;
        }
        if (!markersLoaded || fingerprint != lastMarkersFingerprint) {
            lastMarkersFingerprint = fingerprint;
            map.setMarkersJson(markersJson);
        }
        markersLoaded = true;
        markersComputedAt = computedAt;
        lastMapError = null;
        updateStaleLock();
    }

    /** Whether markers computed at {@code computedAt} are too old to show at {@code now}. */
    static boolean isStale(Instant computedAt, Instant now) {
        return computedAt != null && Duration.between(computedAt, now).compareTo(MAX_MARKER_AGE) > 0;
    }

    /**
     * Locks the map while the markers on screen are older than {@link #MAX_MARKER_AGE}, and unlocks it
     * as soon as a snapshot fresh enough arrives.
     *
     * <p>{@code inert} takes the map out of pointer and keyboard interaction, so a stale marker cannot
     * be clicked or focused behind the blur.
     */
    private void updateStaleLock() {
        var stale = isStale(markersComputedAt, Instant.now());
        staleOverlay.setVisible(stale);
        map.getElement().setAttribute("inert", stale);
    }

    /**
     * Gives up on a load that never reported back, so the map leaves its loading state and the next
     * poll can try again. A late reply from the abandoned load is ignored by its request number.
     */
    private void abandonMarkers(long request) {
        if (request != markerRequest) {
            return;
        }
        markersLoading = false;
        loadingOverlay.setVisible(false);
        updateStaleLock();
        if (attached) {
            report(new DataAccessException(getTranslation("error.api.deadline", client.markerDeadlineSeconds()), null));
        }
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
        return markers(markers, markerTexts());
    }

    /**
     * Same as {@link #markers(List)}, but with the translated strings supplied by the caller, so the
     * payload can be built off the UI thread.
     */
    List<LiveMap.Marker> markers(List<TripMarker> markers, MarkerTexts texts) {
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
                marker(update, update.getLat() + offset.lat(), update.getLon() + offset.lon(), now, texts)
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
        return marker(update, markerTexts());
    }

    /** Same as {@link #marker(TripMarker)}, but with the translated strings supplied by the caller. */
    Optional<LiveMap.Marker> marker(TripMarker update, MarkerTexts texts) {
        return marker(update, update.getLat(), update.getLon(), Instant.now(), texts);
    }

    private Optional<LiveMap.Marker> marker(TripMarker update, double lat, double lon, Instant now, MarkerTexts texts) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            return Optional.empty();
        }
        var label = join(route(update, texts.routeTypeLabels()), trip(update));
        var detail = join(
                update.hasStopName() ? update.getStopName() : null,
                update.hasDelaySeconds() ? formatDelay(texts.delayPattern(), delay(update.getDelaySeconds())) : null);
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
                reachedMinutesAgo,
                update.getTripUpdateId(),
                update.hasRouteType() ? update.getRouteType() : null));
    }

    /** Route part of the label: the route type names the mode, then the short name or id. */
    private static String route(TripMarker update, Map<Integer, String> routeTypeLabels) {
        var route = update.hasRouteShortName()
                ? update.getRouteShortName()
                : update.hasRouteId() ? update.getRouteId() : "";
        if (route.isEmpty() || !update.hasRouteType()) {
            return route;
        }
        // Extended route types have no label in the bundle and fall back to the numeric code.
        var label = routeTypeLabels.getOrDefault(update.getRouteType(), String.valueOf(update.getRouteType()));
        return label + " " + route;
    }

    /**
     * The translated strings the marker payload needs, captured on the UI thread so the background
     * load can build the whole payload without touching the UI.
     *
     * @param routeTypeLabels GTFS route type to its translated label
     * @param delayPattern MessageFormat pattern for the popup's delay text, with {0} for the seconds
     */
    record MarkerTexts(Map<Integer, String> routeTypeLabels, String delayPattern) {}

    private MarkerTexts markerTexts() {
        var labels = new LinkedHashMap<Integer, String>();
        for (var type : Filters.ROUTE_TYPES) {
            labels.put(type, getTranslation("filter.routeType." + type));
        }
        return new MarkerTexts(labels, getTranslation("live.map.delay", "{0}"));
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
     * Formats the delay into the popup pattern off the UI thread. A bundle pattern carries {0}; the
     * no-provider fallback ("!{key}!") does not and is returned as-is, exactly as getTranslation
     * would return it.
     */
    private static String formatDelay(String pattern, String delay) {
        return pattern.contains("{0}") ? MessageFormat.format(pattern, delay) : pattern;
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
