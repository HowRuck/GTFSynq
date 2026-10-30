package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import java.util.List;

/**
 * A MapLibre map of one trip: its stop path and the stop it has currently reached.
 *
 * <p>The element draws a line through the static coordinates of the trip's stops and marks each
 * stop as reached, current or upcoming. GTFS-RT publishes predictions, not vehicle positions, so the
 * current stop is the most advanced stop whose predicted time is not in the future; the marker sits
 * on that stop rather than inventing a position between stops.
 *
 * <p>The stops are pushed as one compact {@code [lat, lon, name, state]} tuple per stop, like the
 * live overview map, so a poll replaces a small JSON property instead of rebuilding the element.
 */
@Tag("trip-map")
@NpmPackage(value = "maplibre-gl", version = "6.16.0")
@JsModule("./trip-map.ts")
public class TripMap extends Component {

    /** How a stop relates to the trip's progress. The order is the wire value. */
    public enum StopState {
        UPCOMING,
        REACHED,
        CURRENT
    }

    /** One stop of the trip, with its static coordinates and current progress state. */
    public record Stop(double lat, double lon, String name, StopState state) {}

    public TripMap() {
        getElement()
                .getStyle()
                .set("display", "block")
                .set("width", "100%")
                .set("height", "100%")
                .set("border-radius", "var(--vaadin-radius-m)")
                .set("overflow", "hidden")
                .set("background", "var(--vaadin-background-color, transparent)");
    }

    /**
     * Replaces the drawn stops. Stops without finite coordinates are dropped because neither
     * MapLibre nor JSON can represent them.
     *
     * @param stops the trip's stops in sequence order, an empty list clears the map
     */
    public void setStops(List<Stop> stops) {
        getElement().setProperty("stops", json(stops));
    }

    static String json(List<Stop> stops) {
        var json = new StringBuilder("[");
        for (var stop : stops) {
            if (!Double.isFinite(stop.lat()) || !Double.isFinite(stop.lon())) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('[')
                    .append(JsonText.coordinate(stop.lat()))
                    .append(',')
                    .append(JsonText.coordinate(stop.lon()))
                    .append(',')
                    .append(JsonText.quote(stop.name()))
                    .append(',')
                    .append(
                            stop.state() == null
                                    ? StopState.UPCOMING.ordinal()
                                    : stop.state().ordinal())
                    .append(']');
        }
        return json.append(']').toString();
    }
}
