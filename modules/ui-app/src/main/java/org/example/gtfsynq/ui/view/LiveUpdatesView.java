package org.example.gtfsynq.ui.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.MainLayout;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.example.gtfsynq.ui.component.LiveMap;
import org.example.gtfsynq.ui.component.LiveUpdatesGrid;
import org.example.gtfsynq.ui.component.PageHeader;

/** Live trip updates, refreshed while the view is attached. */
@Route(value = "live", layout = MainLayout.class)
public class LiveUpdatesView extends VerticalLayout implements HasDynamicTitle {

    private static final int POLL_INTERVAL_MS = 60_000;

    private final LiveUpdatesGrid table;
    private Registration pollRegistration;

    @Inject
    public LiveUpdatesView(GtfsQueryClient client) {
        table = new LiveUpdatesGrid(client, "lastSeenAt", SortDirection.DESCENDING);

        // Feed, agency, route and route type are inline header filters inside the grid.
        var refresh = new Button(getTranslation("live.refresh"), event -> table.refresh());
        refresh.setIcon(VaadinIcon.REFRESH.create());

        var map = new LiveMap();
        map.getElement().setAttribute("aria-label", getTranslation("live.map.aria"));
        // The trip rows the grid loads already carry the coordinates of the most advanced stop, so
        // the map follows the table's filters and paging without a second query.
        table.onTripsLoaded(trips -> map.setMarkers(markers(trips)));

        setSizeFull();
        add(
                PageHeader.create(getTranslation("page.live.title"), getTranslation("page.live.subtitle"), refresh),
                map,
                table);
        setFlexGrow(0, map);
        setFlexGrow(1, table);
    }

    /** One marker per trip that has static coordinates for its most advanced stop. */
    private List<LiveMap.Marker> markers(List<TripUpdate> trips) {
        return trips.stream().map(this::marker).flatMap(Optional::stream).toList();
    }

    private Optional<LiveMap.Marker> marker(TripUpdate update) {
        if (!update.hasLatestStopLat() || !update.hasLatestStopLon()) {
            return Optional.empty();
        }
        var label = join(label(update), trip(update));
        var detail = join(
                update.hasLatestStopName() ? update.getLatestStopName() : null,
                update.hasCurrentDelay() ? getTranslation("live.map.delay", delay(update.getCurrentDelay())) : null);
        return Optional.of(new LiveMap.Marker(
                update.getLatestStopLat(),
                update.getLatestStopLon(),
                label,
                detail,
                update.hasCurrentDelay() ? update.getCurrentDelay() : null));
    }

    private static String label(TripUpdate update) {
        if (update.hasRouteShortName()) {
            return update.getRouteShortName();
        }
        return update.hasRouteId() ? update.getRouteId() : "";
    }

    private static String trip(TripUpdate update) {
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

    @Override
    public String getPageTitle() {
        return getTranslation("document.title", getTranslation("page.live.title"));
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
}
