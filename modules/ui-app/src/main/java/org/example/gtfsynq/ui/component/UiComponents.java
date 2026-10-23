package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.format.UiFormat;

/** Small presentational building blocks used inside grid cells and cards. */
public final class UiComponents {

    private UiComponents() {}

    public static Badge badge(String text) {
        return new Badge(text);
    }

    public static Component routeColor(Route route) {
        if (!route.hasColor() || route.getColor().isBlank()) {
            return new Span("—");
        }
        var color = route.getColor().startsWith("#") ? route.getColor() : "#" + route.getColor();
        return new Span(color);
    }

    public static Component sourceLink(StaticFeed feed) {
        if (!feed.hasSourceUrl() || feed.getSourceUrl().isBlank()) {
            return new Span("—");
        }
        var link = new Anchor(feed.getSourceUrl(), feed.getSourceUrl());
        link.setTarget("_blank");
        return link;
    }

    public static Card statCard(String label, String value, String hint) {
        var card = new Card();
        card.setWidth("14rem");
        card.setTitle(label);
        card.add(new H3(value));
        if (hint != null) {
            card.addToFooter(new Span(hint));
        }
        return card;
    }

    /** Formats a nullable string for a cell. */
    public static String nullable(String value) {
        return UiFormat.text(value);
    }
}
