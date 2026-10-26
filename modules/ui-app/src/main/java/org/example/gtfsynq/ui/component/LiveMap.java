package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import java.util.List;

/**
 * A MapLibre GL map showing where the live trips currently are.
 *
 * <p>A GTFS-RT trip update carries predictions, not a vehicle position, so the most useful
 * "location" is the most advanced stop the trip is predicted to reach. The API resolves that stop's
 * static coordinates for each trip row, and this component plots one marker per trip. The element
 * clusters the markers, so a whole city stays one readable bubble until the user zooms in.
 *
 * <p>The element is a plain custom element ({@code live-map.ts}); markers are passed as a JSON
 * string property, so a poll only sends the marker list and never re-creates the map.
 */
@Tag("live-map")
@NpmPackage(value = "maplibre-gl", version = "6.16.0")
@JsModule("./live-map.ts")
public class LiveMap extends Component {

    /** One trip's latest reported position. */
    public record Marker(double lat, double lon, String label, String detail, Integer delaySeconds) {}

    public LiveMap() {
        getElement()
                .getStyle()
                .set("display", "block")
                .set("width", "100%")
                .set("height", "20rem")
                .set("border-radius", "var(--vaadin-radius-m)")
                .set("overflow", "hidden");
    }

    /**
     * Replaces the markers shown on the map.
     *
     * <p>Markers without finite coordinates are dropped: MapLibre cannot render them, and a JSON
     * number cannot represent them either.
     *
     * @param markers one marker per trip, an empty list clears the map
     */
    public void setMarkers(List<Marker> markers) {
        getElement().setProperty("markers", json(markers));
    }

    static String json(List<Marker> markers) {
        var json = new StringBuilder("[");
        for (var marker : markers) {
            if (!Double.isFinite(marker.lat()) || !Double.isFinite(marker.lon())) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"lat\":")
                    .append(marker.lat())
                    .append(",\"lon\":")
                    .append(marker.lon())
                    .append(",\"label\":")
                    .append(quote(marker.label()))
                    .append(",\"detail\":")
                    .append(quote(marker.detail()))
                    .append(",\"delaySeconds\":")
                    .append(marker.delaySeconds() == null ? "null" : marker.delaySeconds())
                    .append('}');
        }
        return json.append(']').toString();
    }

    /** Quotes a string for JSON without pulling a serialiser into the UI module. */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        var quoted = new StringBuilder(value.length() + 2).append('"');
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
