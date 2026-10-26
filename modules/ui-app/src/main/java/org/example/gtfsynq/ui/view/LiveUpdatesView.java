package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
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
    private Registration pollRegistration;
    private boolean attached;
    private boolean markersLoading;
    private String lastMapError;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        this.client = client;
        table = new LiveUpdatesGrid(client, "lastSeenAt", SortDirection.DESCENDING);

        // Feed, agency, route and route type are inline header filters inside the grid.
        var refresh = new Button(getTranslation("live.refresh"), event -> {
            table.refresh();
            getUI().ifPresent(this::loadMarkers);
        });
        refresh.setIcon(VaadinIcon.REFRESH.create());

        map.getElement().setAttribute("aria-label", getTranslation("live.map.aria"));

        setSizeFull();
        add(
                PageHeader.create(getTranslation("page.live.title"), getTranslation("page.live.subtitle"), refresh),
                map,
                table);
        setFlexGrow(0, map);
        setFlexGrow(1, table);
    }

    /**
     * Loads every marker off the UI thread.
     *
     * <p>The map has its own query rather than following the table: the table shows one page at a
     * time, but the map is an overview of every live trip. A poll that arrives while the previous
     * load is still running is dropped instead of stacking requests.
     */
    private void loadMarkers(UI ui) {
        if (markersLoading) {
            return;
        }
        markersLoading = true;
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
                if (!attached) {
                    return;
                }
                if (error != null) {
                    // Keep the last good markers on screen rather than blanking the map.
                    report(error);
                    return;
                }
                lastMapError = null;
                map.setMarkers(markers(loaded));
            });
        });
    }

    /** One marker per trip that has coordinates for its most advanced stop. */
    private List<LiveMap.Marker> markers(List<TripMarker> markers) {
        return markers.stream().map(this::marker).flatMap(Optional::stream).toList();
    }

    Optional<LiveMap.Marker> marker(TripMarker update) {
        if (!Double.isFinite(update.getLat()) || !Double.isFinite(update.getLon())) {
            return Optional.empty();
        }
        var label = join(route(update), trip(update));
        var detail = join(
                update.hasStopName() ? update.getStopName() : null,
                update.hasDelaySeconds() ? getTranslation("live.map.delay", delay(update.getDelaySeconds())) : null);
        return Optional.of(new LiveMap.Marker(
                update.getLat(),
                update.getLon(),
                label,
                detail,
                update.hasDelaySeconds() ? update.getDelaySeconds() : null));
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
            loadMarkers(ui);
        });
        loadMarkers(ui);
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
}
