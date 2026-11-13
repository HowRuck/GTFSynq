package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.ui.format.UiFormat;

/** Small presentational building blocks used inside grid cells and cards. */
public final class UiComponents {

    /** Longest source URL shown in the feeds table before it is shortened. */
    public static final int SOURCE_URL_WIDTH = 48;

    /** Longest object key shown in the feeds table before it is shortened. */
    public static final int OBJECT_KEY_WIDTH = 40;

    private UiComponents() {}

    public static Component routeColor(Route route) {
        if (!route.hasColor() || route.getColor().isBlank()) {
            return new Span(UiFormat.NOT_AVAILABLE);
        }
        var color = route.getColor().startsWith("#") ? route.getColor() : "#" + route.getColor();
        var swatch = new Span();
        swatch.addClassName("route-color-swatch");
        swatch.getStyle().set("background-color", color);
        swatch.getElement().setAttribute("aria-hidden", "true");

        var cell = new HorizontalLayout(swatch, new Span(color));
        cell.setPadding(false);
        cell.setSpacing(false);
        cell.setAlignItems(Alignment.CENTER);
        cell.addClassName("route-color");
        cell.getElement().setAttribute("title", color);
        return cell;
    }

    /**
     * Last poll outcome of a feed as a badge, coloured by how healthy it is. Feeds that were never
     * polled show "N/A", and "Not Modified" stays in the quiet default colour.
     */
    public static Component pollStatus(Component context, StaticFeed feed) {
        if (!feed.hasPollStatus()) {
            return new Span(UiFormat.NOT_AVAILABLE);
        }
        var status = feed.getPollStatus();
        var badge = new Badge(context.getTranslation("feed.pollStatus." + status.name()));
        badge.addThemeVariants(BadgeVariant.SMALL);
        switch (status) {
            case POLL_STATUS_OK -> badge.addThemeVariants(BadgeVariant.SUCCESS);
            case POLL_STATUS_RATE_LIMITED -> badge.addThemeVariants(BadgeVariant.WARNING);
            case POLL_STATUS_HTTP_ERROR, POLL_STATUS_ERROR -> badge.addThemeVariants(BadgeVariant.ERROR);
            default -> {
                // Not modified is the quiet default colour: nothing new, nothing wrong.
            }
        }
        return badge;
    }

    public static Component sourceLink(StaticFeed feed) {
        if (!feed.hasSourceUrl() || feed.getSourceUrl().isBlank()) {
            return new Span(UiFormat.NOT_AVAILABLE);
        }
        var link = new Anchor(feed.getSourceUrl(), UiFormat.elide(feed.getSourceUrl(), SOURCE_URL_WIDTH));
        link.setTitle(feed.getSourceUrl());
        link.setTarget("_blank");
        return link;
    }

    /**
     * Text cell shortened to {@code maxLength} characters, with the full value as a tooltip.
     */
    public static Component truncated(String value, int maxLength) {
        var span = new Span(UiFormat.elide(value, maxLength));
        span.setTitle(UiFormat.text(value));
        return span;
    }

    /**
     * ETag cell: shortened to keep the column narrow, with the full value available as a tooltip.
     */
    public static Component etag(String value) {
        var span = new Span(UiFormat.elideId(value));
        span.setTitle(UiFormat.text(value));
        return span;
    }

    /** Overview KPI: a quiet label and a dominant, tabular value. */
    public static Card statCard(String label, String value) {
        var card = new Card();
        var title = new Span(label);
        title.addClassName("stat-label");
        var amount = new Span(value);
        amount.addClassName("stat-value");
        var content = new VerticalLayout(title, amount);
        content.setPadding(false);
        content.setSpacing(false);
        card.add(content);
        return card;
    }

    /** Formats a nullable string for a cell. */
    public static String nullable(String value) {
        return UiFormat.text(value);
    }
}
