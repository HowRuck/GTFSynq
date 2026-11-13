package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.ComboBoxVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;

/**
 * Reusable filter controls for the table views.
 *
 * <p>Every filter uses the small variant, including the ones the live table places inside its
 * header row, so the controls above and inside the tables share one height and font size.
 */
public final class Filters {

    /**
     * GTFS route types the translations cover. Extended route types (100+) are valid but have no
     * label here, so callers fall back to the numeric code for those.
     */
    public static final List<Integer> ROUTE_TYPES = List.of(0, 1, 2, 3, 4, 5, 6, 7, 11, 12);

    private Filters() {}

    /** Feed selector; an empty value means "all feeds". */
    public static ComboBox<String> feed(GtfsQueryClient client, String placeholder, Consumer<String> onChange) {
        var feed = new ComboBox<String>();
        feed.setPlaceholder(placeholder);
        feed.getElement().setAttribute("aria-label", placeholder);
        feed.setClearButtonVisible(true);
        feed.setAllowCustomValue(false);
        feed.setWidth("12rem");
        feed.addThemeVariants(ComboBoxVariant.SMALL);
        feed.addValueChangeListener(event -> onChange.accept(event.getValue()));

        // Load the feed ids off the session-lock thread, like the overview stats.
        var ui = UI.getCurrent();
        if (ui == null) {
            return feed;
        }
        CompletableFuture.runAsync(() -> {
            try {
                var ids = client.feedIds();
                ui.access(() -> feed.setItems(ids));
            } catch (DataAccessException ignored) {
                // The table below shows the gRPC failure; an empty selector is enough here.
            }
        });
        return feed;
    }

    /** Agency selector; an empty value means "all agencies". */
    public static ComboBox<String> agency(GtfsQueryClient client, String placeholder, Consumer<String> onChange) {
        var agency = new ComboBox<String>();
        agency.setPlaceholder(placeholder);
        agency.getElement().setAttribute("aria-label", placeholder);
        agency.setClearButtonVisible(true);
        agency.setAllowCustomValue(false);
        agency.setWidth("16rem");
        agency.addThemeVariants(ComboBoxVariant.SMALL);
        agency.addValueChangeListener(event -> onChange.accept(event.getValue()));

        var ui = UI.getCurrent();
        if (ui == null) {
            return agency;
        }
        CompletableFuture.runAsync(() -> {
            try {
                var names = client.agencyNames(null);
                ui.access(() -> agency.setItems(names));
            } catch (DataAccessException ignored) {
                // The table shows the gRPC failure; an empty selector is enough here.
            }
        });
        return agency;
    }

    public static TextField search(String placeholder, Consumer<String> onChange) {
        var search = new TextField();
        search.setPlaceholder(placeholder);
        search.getElement().setAttribute("aria-label", placeholder);
        search.setClearButtonVisible(true);
        search.setWidth("18rem");
        search.addThemeVariants(TextFieldVariant.SMALL);
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(250);
        search.addValueChangeListener(event -> onChange.accept(event.getValue()));
        return search;
    }

    /** GTFS route-type selector; values match the numeric route_type feed field. */
    public static ComboBox<Integer> routeType(
            String placeholder, Function<Integer, String> label, Consumer<Integer> onChange) {
        var options = new LinkedHashMap<Integer, String>();
        for (var type : ROUTE_TYPES) {
            options.put(type, label.apply(type));
        }
        return select(placeholder, "12rem", options, onChange);
    }

    /** GTFS direction-id selector; 0 and 1 are the only standard values. */
    public static ComboBox<Integer> direction(
            String placeholder, Function<Integer, String> label, Consumer<Integer> onChange) {
        var options = new LinkedHashMap<Integer, String>();
        for (var value : List.of(0, 1)) {
            options.put(value, label.apply(value));
        }
        return select(placeholder, "10rem", options, onChange);
    }

    /** GTFS location-type selector: stop, station, entrance, node or boarding area. */
    public static ComboBox<Integer> locationType(
            String placeholder, Function<Integer, String> label, Consumer<Integer> onChange) {
        var options = new LinkedHashMap<Integer, String>();
        for (var type : List.of(0, 1, 2, 3, 4)) {
            options.put(type, label.apply(type));
        }
        return select(placeholder, "13rem", options, onChange);
    }

    /** Feed kind selector: static or realtime; an empty value means both. */
    public static ComboBox<String> kind(String placeholder, Function<String, String> label, Consumer<String> onChange) {
        var options = new LinkedHashMap<String, String>();
        for (var kind : List.of("static", "realtime")) {
            options.put(kind, label.apply(kind));
        }
        return select(placeholder, "12rem", options, onChange);
    }

    private static <T> ComboBox<T> select(
            String placeholder, String width, Map<T, String> options, Consumer<T> onChange) {
        var select = new ComboBox<T>();
        select.setPlaceholder(placeholder);
        select.getElement().setAttribute("aria-label", placeholder);
        select.setClearButtonVisible(true);
        select.setAllowCustomValue(false);
        select.setWidth(width);
        select.addThemeVariants(ComboBoxVariant.SMALL);
        select.setItems(options.keySet());
        select.setItemLabelGenerator(options::get);
        select.addValueChangeListener(event -> onChange.accept(event.getValue()));
        return select;
    }
}
