package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.shared.protocol.query.Stop;
import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.Trip;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.format.UiFormat;

/**
 * Column definitions for the console tables.
 *
 * <p>Column keys double as the backend sort keys, so they must match the sort maps in
 * {@code GtfsQueryRepository}. Headers are translated through the grid, so they follow the locale
 * of the view that built it. Columns are sized to their content by the grids that host them, so no
 * widths are set here.
 */
public final class Columns {

    private Columns() {}

    public static void feedSummary(Grid<StaticFeed> grid) {
        grid.addComponentColumn(feed -> UiComponents.badge(feed.getFeedId()))
                .setHeader(grid.getTranslation("column.feed"));
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader(grid.getTranslation("column.sha256"));
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes())).setHeader(grid.getTranslation("column.size"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader(grid.getTranslation("column.loaded"));
    }

    public static void feeds(Grid<StaticFeed> grid) {
        grid.addComponentColumn(feed -> UiComponents.badge(feed.getFeedId()))
                .setHeader(grid.getTranslation("column.feed"));
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader(grid.getTranslation("column.sha256"));
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes())).setHeader(grid.getTranslation("column.size"));
        grid.addColumn(StaticFeed::getObjectKey).setHeader(grid.getTranslation("column.objectKey"));
        grid.addComponentColumn(UiComponents::sourceLink).setHeader(grid.getTranslation("column.source"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getIngestedAt()))
                .setHeader(grid.getTranslation("column.ingested"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader(grid.getTranslation("column.loaded"));
    }

    public static void routes(Grid<Route> grid) {
        grid.addColumn(Route::getFeedId).setHeader(grid.getTranslation("column.feed"));
        grid.addColumn(Route::getRouteId)
                .setHeader(grid.getTranslation("column.routeId"))
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);
        grid.addColumn(route -> route.hasShortName() ? route.getShortName() : "")
                .setHeader(grid.getTranslation("column.shortName"))
                .setKey("shortName")
                .setSortProperty("shortName")
                .setSortable(true);
        grid.addColumn(route -> route.hasLongName() ? route.getLongName() : "")
                .setHeader(grid.getTranslation("column.longName"))
                .setKey("longName")
                .setSortProperty("longName")
                .setSortable(true);
        grid.addColumn(Route::getRouteType)
                .setHeader(grid.getTranslation("column.type"))
                .setKey("routeType")
                .setSortProperty("routeType")
                .setSortable(true);
        grid.addComponentColumn(UiComponents::routeColor).setHeader(grid.getTranslation("column.color"));
    }

    public static void stops(Grid<Stop> grid) {
        grid.addColumn(Stop::getFeedId).setHeader(grid.getTranslation("column.feed"));
        grid.addColumn(Stop::getStopId)
                .setHeader(grid.getTranslation("column.stopId"))
                .setKey("stopId")
                .setSortProperty("stopId")
                .setSortable(true);
        grid.addColumn(Stop::getStopName)
                .setHeader(grid.getTranslation("column.name"))
                .setKey("stopName")
                .setSortProperty("stopName")
                .setSortable(true);
        grid.addColumn(stop -> stop.hasLat() ? UiFormat.coordinate(stop.getLat()) : "—")
                .setHeader(grid.getTranslation("column.latitude"));
        grid.addColumn(stop -> stop.hasLon() ? UiFormat.coordinate(stop.getLon()) : "—")
                .setHeader(grid.getTranslation("column.longitude"));
        grid.addColumn(stop -> stop.hasLocationType() ? String.valueOf(stop.getLocationType()) : "—")
                .setHeader(grid.getTranslation("column.locationType"))
                .setKey("locationType")
                .setSortProperty("locationType")
                .setSortable(true);
        grid.addColumn(stop -> stop.hasParentStation() ? stop.getParentStation() : "—")
                .setHeader(grid.getTranslation("column.parentStation"));
    }

    public static void trips(Grid<Trip> grid) {
        grid.addColumn(Trip::getFeedId).setHeader(grid.getTranslation("column.feed"));
        grid.addColumn(Trip::getTripId)
                .setHeader(grid.getTranslation("column.tripId"))
                .setKey("tripId")
                .setSortProperty("tripId")
                .setSortable(true);
        grid.addColumn(Trip::getRouteId)
                .setHeader(grid.getTranslation("column.routeId"))
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);
        grid.addColumn(Trip::getServiceId)
                .setHeader(grid.getTranslation("column.serviceId"))
                .setKey("serviceId")
                .setSortProperty("serviceId")
                .setSortable(true);
        grid.addColumn(trip -> trip.hasDirectionId() ? String.valueOf(trip.getDirectionId()) : "—")
                .setHeader(grid.getTranslation("column.direction"));
    }

    /**
     * Columns of the live updates tree.
     *
     * <p>The hierarchy column doubles as the trip / stop label: a trip row shows its trip id, and
     * its stop rows are indented below it with their stop id.
     */
    public static void liveUpdates(TreeGrid<LiveUpdateRow> grid) {
        grid.addComponentHierarchyColumn(row -> liveLabel(grid, row))
                .setHeader(grid.getTranslation("column.tripOrStop"))
                .setKey("tripId")
                .setSortProperty("tripId")
                .setSortable(true);

        grid.addComponentColumn(row -> row instanceof TripUpdateRow trip
                        ? UiComponents.badge(trip.update().getFeedId())
                        : new Span())
                .setHeader(grid.getTranslation("column.feed"));

        grid.addColumn(row -> row instanceof TripUpdateRow trip
                        ? UiFormat.text(trip.update().getRouteId())
                        : "")
                .setHeader(grid.getTranslation("column.routeId"))
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);

        grid.addColumn(row -> row instanceof TripUpdateRow trip
                        ? UiFormat.text(trip.update().getStartDate())
                        : "")
                .setHeader(grid.getTranslation("column.startDate"));

        grid.addColumn(row -> row instanceof TripUpdateRow trip
                        ? UiFormat.time(trip.update().getStartTime())
                        : "")
                .setHeader(grid.getTranslation("column.startTime"));

        grid.addColumn(row -> row instanceof TripUpdateRow trip ? currentDelay(trip.update()) : "")
                .setHeader(grid.getTranslation("column.currentDelay"))
                .setTextAlign(ColumnTextAlign.END)
                .setPartNameGenerator(row -> row instanceof TripUpdateRow trip
                        ? delayPart(
                                trip.update().hasCurrentDelay() ? trip.update().getCurrentDelay() : null)
                        : null);

        grid.addColumn(row -> row instanceof StopTimeUpdateRow stop
                        ? String.valueOf(stop.update().getStopSequence())
                        : "")
                .setHeader(grid.getTranslation("column.stopSequence"))
                .setTextAlign(ColumnTextAlign.END);

        grid.addColumn(row -> row instanceof StopTimeUpdateRow stop ? eventTime(stop.update(), true) : "")
                .setHeader(grid.getTranslation("column.arrivalTime"));

        grid.addColumn(row -> row instanceof StopTimeUpdateRow stop ? delay(stop.update(), true) : "")
                .setHeader(grid.getTranslation("column.arrivalDelay"))
                .setTextAlign(ColumnTextAlign.END)
                .setPartNameGenerator(
                        row -> row instanceof StopTimeUpdateRow stop ? delayPart(stop.update(), true) : null);

        grid.addColumn(row -> row instanceof StopTimeUpdateRow stop ? eventTime(stop.update(), false) : "")
                .setHeader(grid.getTranslation("column.departureTime"));

        grid.addColumn(row -> row instanceof StopTimeUpdateRow stop ? delay(stop.update(), false) : "")
                .setHeader(grid.getTranslation("column.departureDelay"))
                .setTextAlign(ColumnTextAlign.END)
                .setPartNameGenerator(
                        row -> row instanceof StopTimeUpdateRow stop ? delayPart(stop.update(), false) : null);

        grid.addColumn(row -> row instanceof TripUpdateRow trip
                        ? UiFormat.dateTime(trip.update().getFeedTs())
                        : "")
                .setHeader(grid.getTranslation("column.feedTimestamp"))
                .setKey("feedTs")
                .setSortProperty("feedTs")
                .setSortable(true);

        grid.addColumn(row -> row instanceof TripUpdateRow trip
                        ? UiFormat.dateTime(trip.update().getLastSeenAt())
                        : "")
                .setHeader(grid.getTranslation("column.lastSeen"))
                .setKey("lastSeenAt")
                .setSortProperty("lastSeenAt")
                .setSortable(true);
    }

    private static Component liveLabel(TreeGrid<LiveUpdateRow> grid, LiveUpdateRow row) {
        return switch (row) {
            case TripUpdateRow trip -> new Span(UiFormat.text(trip.update().getTripId()));
            case StopTimeUpdateRow stop -> new Span(UiFormat.text(stopId(stop.update())));
            case LoadingRow ignored -> loadingRow(grid);
        };
    }

    private static Component loadingRow(TreeGrid<LiveUpdateRow> grid) {
        var progress = new ProgressBar();
        progress.setIndeterminate(true);
        progress.setWidth("5rem");

        var loading = new HorizontalLayout(progress, new Span(grid.getTranslation("grid.loading")));
        loading.setPadding(false);
        loading.setAlignItems(Alignment.CENTER);
        return loading;
    }

    private static String stopId(StopTimeUpdate stop) {
        if (stop.hasStopId()) {
            return stop.getStopId();
        }
        return stop.hasAssignedStopId() ? stop.getAssignedStopId() : null;
    }

    private static String eventTime(StopTimeUpdate stop, boolean arrival) {
        if (arrival) {
            return UiFormat.eventTime(
                    stop.getArrivalTime(),
                    stop.getScheduledArrivalTime(),
                    stop.hasArrivalDelay() ? stop.getArrivalDelay() : null);
        }
        return UiFormat.eventTime(
                stop.getDepartureTime(),
                stop.getScheduledDepartureTime(),
                stop.hasDepartureDelay() ? stop.getDepartureDelay() : null);
    }

    private static String currentDelay(TripUpdate trip) {
        return UiFormat.delay(trip.hasCurrentDelay() ? trip.getCurrentDelay() : null);
    }

    private static String delay(StopTimeUpdate stop, boolean arrival) {
        return UiFormat.delay(delaySeconds(stop, arrival));
    }

    /**
     * Part name that colors a delay cell: blue when early, green when on time, orange when late,
     * and red with extra weight once the delay reaches five minutes.
     */
    private static String delayPart(StopTimeUpdate stop, boolean arrival) {
        return delayPart(delaySeconds(stop, arrival));
    }

    private static String delayPart(Integer seconds) {
        if (seconds == null) {
            return null;
        }
        if (seconds <= -60) {
            return "delay-early";
        }
        if (seconds >= 300) {
            return "delay-severe";
        }
        if (seconds >= 60) {
            return "delay-late";
        }
        return "delay-on-time";
    }

    private static Integer delaySeconds(StopTimeUpdate stop, boolean arrival) {
        return arrival
                ? (stop.hasArrivalDelay() ? stop.getArrivalDelay() : null)
                : (stop.hasDepartureDelay() ? stop.getDepartureDelay() : null);
    }
}
