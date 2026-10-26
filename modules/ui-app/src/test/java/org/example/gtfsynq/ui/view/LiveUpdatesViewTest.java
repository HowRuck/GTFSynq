package org.example.gtfsynq.ui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.component.UI;
import java.util.List;
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

    @Test
    void fingerprintsRawSnapshotsByContent() {
        var first = List.of(marker(3, "10", "Trip 1"));
        var again = List.of(marker(3, "10", "Trip 1"));
        var changed = List.of(marker(3, "10", "Trip 2"));

        assertEquals(LiveUpdatesView.fingerprint(first), LiveUpdatesView.fingerprint(again));
        assertNotEquals(LiveUpdatesView.fingerprint(first), LiveUpdatesView.fingerprint(changed));
    }

    @Test
    void keepsASingleTripAtTheStopCoordinate() {
        withView(view -> {
            var markers = view.markers(List.of(marker(3, "10", "Trip 1")));

            assertEquals(1, markers.size());
            assertEquals(52.5, markers.getFirst().lat());
            assertEquals(13.4, markers.getFirst().lon());
        });
    }

    @Test
    void spreadsTripsThatShareOneStopCoordinate() {
        withView(view -> {
            var first = marker(3, "10", "Trip 1");
            var second = marker(3, "10", "Trip 2");

            var markers = view.markers(List.of(first, second));

            assertEquals(2, markers.size());
            assertTrue(
                    Math.abs(markers.get(0).lat() - markers.get(1).lat()) > 0
                            || Math.abs(markers.get(0).lon() - markers.get(1).lon()) > 0,
                    "co-located markers must not collapse into one visible dot");
            for (var marker : markers) {
                // The spread is a few metres, well below the ~0.1 m coordinate rounding limit.
                assertTrue(Math.abs(marker.lat() - 52.5) < 0.001);
                assertTrue(Math.abs(marker.lon() - 13.4) < 0.001);
            }
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
