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
 * "location" is the most advanced stop the trip has already reached. The API resolves that stop's
 * static coordinates for every live trip, and this component plots one marker per trip. The markers
 * are loaded independently of the live table's paging, so the map is always an overview of the whole
 * live set. The element clusters the markers, so a whole city stays one readable bubble until the
 * user zooms in.
 *
 * <p>The element is a plain custom element ({@code live-map.ts}); markers are passed as a compact
 * JSON property (one {@code [lat, lon, label, detail, delay]} tuple per trip), so a poll only sends
 * the marker list and never re-creates the map, and an unchanged snapshot is not sent at all.
 * Clustering and the basemap live in the element: it uses OpenFreeMap vector styles and falls back
 * to raster tiles when that CDN cannot be reached, so an offline deployment shows markers instead of
 * a blank map.
 */
@Tag("live-map")
@NpmPackage(value = "maplibre-gl", version = "6.16.0")
@JsModule("./live-map.ts")
public class LiveMap extends Component {

    /** One trip's latest reported position and how long ago it reached that stop. */
    public record Marker(
            double lat, double lon, String label, String detail, Integer delaySeconds, Integer reachedMinutesAgo) {}

    public LiveMap() {
        getElement()
                .getStyle()
                .set("display", "block")
                .set("width", "100%")
                // Tall enough to read a city, but bounded so the grid below keeps usable space on
                // short screens. Relative units only, so it scales with the root font size.
                .set("height", "clamp(24rem, 42vh, 40rem)")
                .set("border-radius", "var(--vaadin-radius-m)")
                .set("overflow", "hidden")
                // The canvas is transparent until a style paints its background layer, so an
                // unreachable basemap would otherwise leave a hole in the page.
                .set("background", "var(--vaadin-background-color, transparent)");
    }

    /**
     * Points the map at an alternative MapLibre style, for example a self-hosted tile server.
     *
     * <p>An empty value restores the built-in OpenFreeMap styles.
     *
     * @param styleUrl MapLibre style URL
     */
    public void setStyleUrl(String styleUrl) {
        getElement().setProperty("styleUrl", styleUrl == null ? "" : styleUrl);
    }

    /**
     * Sets the popup texts used to show how long ago the trip reached its stop.
     *
     * @param justNow text for a stop reached less than a minute ago
     * @param minutes MessageFormat-style text with {@code {0}} for the elapsed minutes
     */
    public void setReachedTexts(String justNow, String minutes) {
        getElement().setProperty("reachedJustNow", justNow == null ? "" : justNow);
        getElement().setProperty("reachedMinutes", minutes == null ? "" : minutes);
    }

    /**
     * Adds an optional raster overlay above the vector basemap and below the trip markers, for
     * example Thunderforest's Transport map.
     *
     * <p>An empty URL removes the overlay. The tiles are loaded by the browser, so the URL (and any
     * API key it contains) is visible to the user; use a key restricted to the deployment's referrer.
     *
     * @param tilesUrl raster tile URL template, e.g. {@code https://tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=...}
     * @param opacity blend opacity, clamped to 0..1 by the element
     */
    public void setTransportOverlay(String tilesUrl, double opacity) {
        getElement().setProperty("transportOverlayUrl", tilesUrl == null ? "" : tilesUrl);
        getElement().setProperty("transportOverlayOpacity", opacity);
    }

    /**
     * Replaces the markers shown on the map.
     *
     * <p>Markers without finite coordinates are dropped: MapLibre cannot render them, and a JSON
     * number cannot represent them either. The view only calls this when the marker snapshot
     * changed, so a quiet poll does not touch the element.
     *
     * @param markers one marker per trip, an empty list clears the map
     */
    public void setMarkers(List<Marker> markers) {
        getElement().setProperty("markers", json(markers));
    }

    /**
     * Serialises the markers as one
     * {@code [lat, lon, label, detail, delaySeconds, reachedMinutesAgo]} array per trip.
     *
     * <p>The tuple form keeps the pushed JSON small: at tens of thousands of markers, repeating the
     * keys of an object costs more than the values themselves.
     */
    static String json(List<Marker> markers) {
        var json = new StringBuilder("[");
        for (var marker : markers) {
            if (!Double.isFinite(marker.lat()) || !Double.isFinite(marker.lon())) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('[')
                    .append(JsonText.coordinate(marker.lat()))
                    .append(',')
                    .append(JsonText.coordinate(marker.lon()))
                    .append(',')
                    .append(JsonText.quote(marker.label()))
                    .append(',')
                    .append(JsonText.quote(marker.detail()))
                    .append(',')
                    .append(marker.delaySeconds() == null ? "null" : marker.delaySeconds())
                    .append(',')
                    .append(marker.reachedMinutesAgo() == null ? "null" : marker.reachedMinutesAgo())
                    .append(']');
        }
        return json.append(']').toString();
    }
}
