package org.example.gtfsynq.ui.component;

import com.google.protobuf.Timestamp;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.shared.Registration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.format.UiFormat;

/**
 * The detail pane of the live updates view: one trip's map and stop timeline.
 *
 * <p>A trip update only carries predictions, so the timeline marks a stop as reached once its
 * predicted time is not in the future. The most advanced reached stop is the current position and
 * is drawn with a marker on both the timeline and the map; stops after it are upcoming. Stops the
 * feed did not update are filled in from the static schedule and marked as such, so reference data
 * never looks like a live prediction.
 */
public class TripDetailPanel extends VerticalLayout {

    private final H3 title = new H3();
    private final Span subtitle = new Span();
    private final H4 stopsHeading = new H4();
    private final Span stopsCount = new Span();
    private final Badge feed = new Badge();
    private final Span start = new Span();
    private final Span lastSeen = new Span();
    private final Span delay = new Span();
    private final Button close = new Button(VaadinIcon.CLOSE.create());

    private final TripMap map = new TripMap();
    private final Div mapFrame = new Div(map);
    private final Span noPosition = new Span();
    private final VerticalLayout timeline = new VerticalLayout();
    private final Scroller scroller = new Scroller(timeline, Scroller.ScrollDirection.VERTICAL);
    private final HorizontalLayout loading = new HorizontalLayout();
    private final Span error = new Span();
    private long renderedTripId = Long.MIN_VALUE;

    public TripDetailPanel() {
        setSizeFull();
        setMinWidth("0");
        setMinHeight("0");
        addClassName("trip-detail");

        title.addClassName("trip-detail-title");
        subtitle.addClassName("trip-detail-subtitle");

        var titleBlock = new VerticalLayout(title, subtitle);
        titleBlock.setPadding(false);
        titleBlock.setSpacing(false);
        titleBlock.setMinWidth("0");

        close.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        close.setAriaLabel(getTranslation("live.detail.close"));
        close.getElement().setAttribute("title", getTranslation("live.detail.close"));
        close.addClickListener(event -> fireEvent(new CloseEvent(this, event.isFromClient())));

        delay.addClassName("trip-detail-delay");

        var header = new HorizontalLayout(titleBlock, delay, close);
        header.setPadding(false);
        header.setWidthFull();
        header.setAlignItems(Alignment.START);
        header.setFlexGrow(1, titleBlock);

        var metadata = new HorizontalLayout(feed, start, lastSeen);
        metadata.setPadding(false);
        metadata.setWrap(true);
        metadata.addClassName("trip-detail-metadata");

        map.getElement().setAttribute("aria-label", getTranslation("live.detail.map.aria"));
        mapFrame.setWidthFull();
        mapFrame.addClassName("trip-detail-map");
        noPosition.addClassName("trip-detail-no-position");
        noPosition.setText(getTranslation("live.detail.noPosition"));

        stopsHeading.setText(getTranslation("live.detail.stops"));
        var stopsTitle = new HorizontalLayout(stopsHeading, stopsCount);
        stopsTitle.setPadding(false);
        stopsTitle.setAlignItems(Alignment.BASELINE);
        stopsTitle.addClassName("trip-detail-stops-title");
        stopsTitle.setFlexGrow(1, stopsHeading);

        timeline.setPadding(false);
        timeline.setSpacing(false);
        timeline.setWidthFull();

        scroller.setSizeFull();
        scroller.addClassName("trip-detail-timeline");
        scroller.setVisible(false);

        var progress = new ProgressBar();
        progress.setIndeterminate(true);
        progress.setWidth("5rem");
        loading.add(progress, new Span(getTranslation("live.detail.loading")));
        loading.setPadding(false);
        loading.setAlignItems(Alignment.CENTER);
        loading.addClassName("trip-detail-loading");
        loading.setVisible(false);

        error.addClassName("trip-detail-error");
        error.setVisible(false);

        add(header, metadata, mapFrame, noPosition, stopsTitle, loading, error, scroller);
        setFlexGrow(1, scroller);
        setVisible(false);
    }

    /** Shows the trip header and a loading state while its stop times are on the way. */
    public void showLoading(TripUpdate trip) {
        setVisible(true);
        renderedTripId = Long.MIN_VALUE;
        updateHeader(trip);
        map.setStops(List.of());
        mapFrame.setVisible(false);
        noPosition.setVisible(false);
        timeline.removeAll();
        scroller.setVisible(false);
        stopsCount.setText("");
        stopsHeading.setVisible(false);
        error.setVisible(false);
        loading.setVisible(true);
    }

    /** Shows the trip header and the failure that prevented its stop times from loading. */
    public void showError(TripUpdate trip, String message) {
        setVisible(true);
        updateHeader(trip);
        map.setStops(List.of());
        mapFrame.setVisible(false);
        noPosition.setVisible(false);
        timeline.removeAll();
        scroller.setVisible(false);
        stopsHeading.setVisible(false);
        stopsCount.setText("");
        loading.setVisible(false);
        error.setText(getTranslation("live.detail.error", message));
        error.setVisible(true);
    }

    /** Renders the stop timeline and the map path for a loaded trip. */
    public void showTrip(TripUpdate trip, List<StopTimeUpdate> stops, Instant now) {
        setVisible(true);
        var changedTrip = trip.getTripUpdateId() != renderedTripId;
        renderedTripId = trip.getTripUpdateId();
        // The stop rows carry the same delay the table derives; refreshing them updates the
        // header too, even when the grid's selected trip row is from an earlier poll.
        updateHeader(withCurrentDelay(trip, stops));
        loading.setVisible(false);
        error.setVisible(false);
        stopsHeading.setVisible(true);

        var currentIndex = currentIndex(stops, now);
        var mapStops = new ArrayList<TripMap.Stop>(stops.size());
        timeline.removeAll();

        for (var index = 0; index < stops.size(); index++) {
            var stop = stops.get(index);
            var state = state(index, currentIndex);
            timeline.add(stopRow(stop, state));

            if (stop.hasStopLat() && stop.hasStopLon()) {
                mapStops.add(new TripMap.Stop(
                        stop.getStopLat(), stop.getStopLon(), Columns.stopName(stop), mapState(state)));
            }
        }

        map.setStops(mapStops);
        var hasPosition = !mapStops.isEmpty();
        mapFrame.setVisible(hasPosition);
        noPosition.setVisible(!hasPosition);
        stopsCount.setText(getTranslation("live.detail.stops.count", stops.size()));

        if (stops.isEmpty()) {
            var empty = new Span(getTranslation("live.detail.empty"));
            empty.addClassName("trip-detail-empty");
            timeline.add(empty);
            scroller.setVisible(true);
        } else {
            scroller.setVisible(true);
            // A poll updates the same trip; only a newly opened trip jumps back to its first stop.
            if (changedTrip) {
                scroller.scrollToTop();
            }
        }
    }

    /** The detail pane's close button was pressed. */
    public Registration addCloseListener(ComponentEventListener<CloseEvent> listener) {
        return addListener(CloseEvent.class, listener);
    }

    private void updateHeader(TripUpdate trip) {
        title.setText(UiFormat.text(Columns.tripName(trip)));

        var parts = new ArrayList<String>();
        if (trip.hasRouteType()) {
            parts.add(Columns.routeTypeLabel(this, trip.getRouteType()));
        }
        parts.add(UiFormat.text(Columns.routeName(trip)));
        if (trip.hasAgencyName()) {
            parts.add(trip.getAgencyName());
        }
        subtitle.setText(String.join(" \u00b7 ", parts));

        feed.setText(trip.getFeedId());

        if (trip.hasStartDate() || trip.hasStartTime()) {
            start.setText(getTranslation(
                    "live.detail.start", UiFormat.text(trip.getStartDate()), UiFormat.time(trip.getStartTime())));
            start.setVisible(true);
        } else {
            start.setVisible(false);
        }
        lastSeen.setText(getTranslation("live.detail.lastSeen", UiFormat.dateTime(trip.getLastSeenAt())));

        if (trip.hasCurrentDelay()) {
            var seconds = trip.getCurrentDelay();
            delay.setText(UiFormat.delay(seconds));
            delay.removeClassNames("delay-early", "delay-on-time", "delay-late", "delay-severe");
            var part = Columns.delayPart(seconds);
            if (part != null) {
                delay.addClassName(part);
            }
            delay.setVisible(true);
        } else {
            delay.setVisible(false);
        }
    }

    private Component stopRow(StopTimeUpdate stop, StopState state) {
        var rail = new Div(marker(state, stop));
        rail.addClassName("trip-timeline-rail");

        var name = new Span(Columns.stopName(stop));
        name.addClassName("trip-timeline-name");
        if (stop.hasStopId()) {
            name.getElement().setAttribute("title", stop.getStopId());
        }

        var sequence = new Span(String.valueOf(stop.getStopSequence()));
        sequence.addClassName("trip-timeline-sequence");

        var labels = new HorizontalLayout(name, sequence);
        labels.setPadding(false);
        labels.setWidthFull();
        labels.setWrap(true);
        labels.setAlignItems(Alignment.CENTER);
        labels.setFlexGrow(1, name);

        if (state == StopState.CURRENT) {
            var current = new Badge(getTranslation("live.detail.current"));
            current.addThemeVariants(BadgeVariant.SUCCESS, BadgeVariant.SMALL);
            labels.add(current);
        }
        if (isSkipped(stop)) {
            var skipped = new Badge(getTranslation("live.detail.skipped"));
            skipped.addThemeVariants(BadgeVariant.WARNING, BadgeVariant.SMALL);
            labels.add(skipped);
        }
        if (!stop.getRealtime()) {
            var scheduled = new Badge(getTranslation("live.stop.scheduled"));
            scheduled.addThemeVariants(BadgeVariant.CONTRAST, BadgeVariant.SMALL);
            scheduled.getElement().setAttribute("title", getTranslation("live.stop.scheduled.title"));
            labels.add(scheduled);
        }

        var times = new HorizontalLayout();
        times.setPadding(false);
        times.setWrap(true);
        times.addClassName("trip-timeline-times");
        var arrival = UiFormat.eventTime(
                stop.getArrivalTime(),
                stop.getScheduledArrivalTime(),
                stop.hasArrivalDelay() ? stop.getArrivalDelay() : null);
        var departure = UiFormat.eventTime(
                stop.getDepartureTime(),
                stop.getScheduledDepartureTime(),
                stop.hasDepartureDelay() ? stop.getDepartureDelay() : null);
        if (!UiFormat.NOT_AVAILABLE.equals(arrival)) {
            times.add(new Span(getTranslation("live.detail.arrival", arrival)));
        }
        if (!UiFormat.NOT_AVAILABLE.equals(departure)) {
            times.add(new Span(getTranslation("live.detail.departure", departure)));
        }
        var stopDelay = delaySeconds(stop);
        if (stopDelay != null) {
            var value = new Span(UiFormat.delay(stopDelay));
            var part = Columns.delayPart(stopDelay);
            if (part != null) {
                value.addClassName(part);
            }
            times.add(value);
        }

        var content = new VerticalLayout(labels);
        content.setPadding(false);
        content.setSpacing(false);
        content.addClassName("trip-timeline-content");
        if (times.getComponentCount() > 0) {
            content.add(times);
        }

        var row = new HorizontalLayout(rail, content);
        row.setPadding(false);
        row.setWidthFull();
        row.setAlignItems(Alignment.START);
        row.addClassNames("trip-timeline-row", stateName(state));
        if (isSkipped(stop)) {
            row.addClassName("skipped");
        }
        row.setFlexGrow(1, content);
        return row;
    }

    private static Component marker(StopState state, StopTimeUpdate stop) {
        Icon icon = switch (isSkipped(stop) ? StopState.SKIPPED : state) {
            case REACHED -> VaadinIcon.CHECK.create();
            case CURRENT -> VaadinIcon.MAP_MARKER.create();
            case SKIPPED -> VaadinIcon.CLOSE.create();
            case UPCOMING -> VaadinIcon.CIRCLE.create();
        };
        icon.addClassName("trip-timeline-icon");
        return icon;
    }

    private static StopState state(int index, int currentIndex) {
        if (currentIndex < 0) {
            return StopState.UPCOMING;
        }
        if (index < currentIndex) {
            return StopState.REACHED;
        }
        return index == currentIndex ? StopState.CURRENT : StopState.UPCOMING;
    }

    private static TripMap.StopState mapState(StopState state) {
        return switch (state) {
            case REACHED -> TripMap.StopState.REACHED;
            case CURRENT -> TripMap.StopState.CURRENT;
            case SKIPPED, UPCOMING -> TripMap.StopState.UPCOMING;
        };
    }

    private static String stateName(StopState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The most advanced stop whose predicted time is not in the future, or {@code -1} when the trip
     * has not started or none of its stops carries a time.
     */
    static int currentIndex(List<StopTimeUpdate> stops, Instant now) {
        var current = -1;
        for (var index = 0; index < stops.size(); index++) {
            var stop = stops.get(index);
            // A skipped stop is never the trip's current position.
            var at = isSkipped(stop) ? null : stopInstant(stop);
            if (at != null && !at.isAfter(now)) {
                current = index;
            }
        }
        return current;
    }

    /**
     * The best available time for a stop: the live prediction, the scheduled time shifted by the
     * reported delay, or the bare scheduled time. Some feeds only publish delays.
     */
    static Instant stopInstant(StopTimeUpdate stop) {
        var arrival = instant(stop.getArrivalTime());
        if (arrival != null) {
            return arrival;
        }
        var departure = instant(stop.getDepartureTime());
        if (departure != null) {
            return departure;
        }
        var scheduledArrival = instant(stop.getScheduledArrivalTime());
        if (scheduledArrival != null && stop.hasArrivalDelay()) {
            return scheduledArrival.plusSeconds(stop.getArrivalDelay());
        }
        var scheduledDeparture = instant(stop.getScheduledDepartureTime());
        if (scheduledDeparture != null && stop.hasDepartureDelay()) {
            return scheduledDeparture.plusSeconds(stop.getDepartureDelay());
        }
        return scheduledArrival != null ? scheduledArrival : scheduledDeparture;
    }

    private static Instant instant(Timestamp value) {
        if (value == null || (value.getSeconds() == 0 && value.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    private static boolean isSkipped(StopTimeUpdate stop) {
        return stop.hasScheduleRelationship() && "SKIPPED".equals(stop.getScheduleRelationship());
    }

    /** Overlays the delay of the most advanced stop that reports one, matching the table's value. */
    private static TripUpdate withCurrentDelay(TripUpdate trip, List<StopTimeUpdate> stops) {
        for (var index = stops.size() - 1; index >= 0; index--) {
            var delay = delaySeconds(stops.get(index));
            if (delay != null) {
                return trip.toBuilder().setCurrentDelay(delay).build();
            }
        }
        return trip.toBuilder().clearCurrentDelay().build();
    }

    private static Integer delaySeconds(StopTimeUpdate stop) {
        if (stop.hasArrivalDelay()) {
            return stop.getArrivalDelay();
        }
        return stop.hasDepartureDelay() ? stop.getDepartureDelay() : null;
    }

    private enum StopState {
        UPCOMING,
        REACHED,
        CURRENT,
        SKIPPED
    }

    /** Fired when the user closes the detail pane. */
    public static class CloseEvent extends ComponentEvent<TripDetailPanel> {

        public CloseEvent(TripDetailPanel source, boolean fromClient) {
            super(source, fromClient);
        }
    }
}
