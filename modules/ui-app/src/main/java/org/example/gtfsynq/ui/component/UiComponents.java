package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.format.UiFormat;

/** Small presentational building blocks used inside grid cells. */
public final class UiComponents {

    private UiComponents() {}

    public static Span badge(String text) {
        var badge = new Span(text);
        badge.addClassName("badge");
        return badge;
    }

    public static Component colorSwatch(Route route) {
        if (!route.hasColor() || route.getColor().isBlank()) {
            return new Span("—");
        }
        var color = route.getColor().startsWith("#") ? route.getColor() : "#" + route.getColor();
        var dot = new Span();
        dot.addClassName("color-dot");
        dot.getStyle().set("background-color", color);

        var layout = new HorizontalLayout(dot, new Span(color));
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.setAlignItems(Alignment.CENTER);
        return layout;
    }

    public static Component sourceLink(StaticFeed feed) {
        if (!feed.hasSourceUrl() || feed.getSourceUrl().isBlank()) {
            return new Span("—");
        }
        var link = new Anchor(feed.getSourceUrl(), feed.getSourceUrl());
        link.setTarget("_blank");
        link.addClassName("source-link");
        return link;
    }

    public static Component statCard(String label, String value, String hint) {
        var card = new Div();
        card.addClassName("stat-card");
        card.add(label(label));
        card.add(value(value));
        if (hint != null) {
            card.add(hint(hint));
        }
        return card;
    }

    private static Span label(String text) {
        var span = new Span(text);
        span.addClassName("stat-label");
        return span;
    }

    private static Span value(String text) {
        var span = new Span(text);
        span.addClassName("stat-value");
        return span;
    }

    private static Span hint(String text) {
        var span = new Span(text);
        span.addClassName("stat-hint");
        return span;
    }

    /** Formats a nullable string for a cell. */
    public static String nullable(String value) {
        return UiFormat.text(value);
    }
}
