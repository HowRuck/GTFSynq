package org.example.gtfsynq.ui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.UI;
import org.example.gtfsynq.shared.protocol.query.TripMarker;
import org.example.gtfsynq.ui.client.GtfsQueryClient;
import org.junit.jupiter.api.Test;

class LiveUpdatesViewTest {

    @Test
    void prefixesTheRouteWithItsTranslatedType() {
        withView(view -> {
            var marker = view.marker(marker(3, "10", "Trip 1")).orElseThrow();

            assertEquals(view.getTranslation("filter.routeType.3") + " 10 \u00b7 Trip 1", marker.label());
        });
    }

    @Test
    void fallsBackToTheNumericCodeForExtendedRouteTypes() {
        withView(view -> {
            var marker = view.marker(marker(100, "10", "Trip 1")).orElseThrow();

            assertEquals("100 10 \u00b7 Trip 1", marker.label());
        });
    }

    @Test
    void fallsBackToRouteAndTripIdsWithoutShortNames() {
        withView(view -> {
            var update = TripMarker.newBuilder()
                    .setLat(52.5)
                    .setLon(13.4)
                    .setRouteId("route-id")
                    .setTripId("trip-id")
                    .build();

            assertEquals(
                    "route-id \u00b7 trip-id", view.marker(update).orElseThrow().label());
        });
    }

    @Test
    void showsTheStopAndDelayInThePopup() {
        withView(view -> {
            var update = marker(3, "10", "Trip 1").toBuilder()
                    .setStopName("Central Station")
                    .setDelaySeconds(120)
                    .build();

            var marker = view.marker(update).orElseThrow();

            assertEquals("Central Station \u00b7 " + view.getTranslation("live.map.delay", "+120"), marker.detail());
            assertEquals(120, marker.delaySeconds());
        });
    }

    @Test
    void dropsTripsWithoutFiniteCoordinates() {
        withView(view -> {
            var update =
                    marker(3, "10", "Trip 1").toBuilder().setLat(Double.NaN).build();

            assertTrue(view.marker(update).isEmpty());
        });
    }

    private static void withView(java.util.function.Consumer<LiveUpdatesView> assertions) {
        UI.setCurrent(new UI());
        try {
            assertions.accept(new LiveUpdatesView(new GtfsQueryClient()));
        } finally {
            UI.setCurrent(null);
        }
    }

    private static TripMarker marker(int routeType, String routeShortName, String tripShortName) {
        return TripMarker.newBuilder()
                .setLat(52.5)
                .setLon(13.4)
                .setRouteType(routeType)
                .setRouteShortName(routeShortName)
                .setTripShortName(tripShortName)
                .build();
    }
}
