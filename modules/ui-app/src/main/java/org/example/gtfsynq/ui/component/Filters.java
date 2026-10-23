package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.function.Consumer;
import org.example.gtfsynq.ui.client.DataAccessException;
import org.example.gtfsynq.ui.client.GtfsQueryClient;

/** Reusable filter controls for the table views. */
public final class Filters {

    private Filters() {}

    /** Feed selector; an empty value means "all feeds". */
    public static ComboBox<String> feed(GtfsQueryClient client, String placeholder, Consumer<String> onChange) {
        var feed = new ComboBox<String>();
        feed.setPlaceholder(placeholder);
        feed.setClearButtonVisible(true);
        feed.setAllowCustomValue(false);
        feed.setWidth("12rem");
        try {
            feed.setItems(client.feedIds());
        } catch (DataAccessException ignored) {
            // The table below shows the gRPC failure; an empty selector is enough here.
        }
        feed.addValueChangeListener(event -> onChange.accept(event.getValue()));
        return feed;
    }

    public static TextField search(String placeholder, Consumer<String> onChange) {
        var search = new TextField();
        search.setPlaceholder(placeholder);
        search.setClearButtonVisible(true);
        search.setWidth("18rem");
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setValueChangeTimeout(250);
        search.addValueChangeListener(event -> onChange.accept(event.getValue()));
        return search;
    }
}
