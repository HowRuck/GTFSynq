package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.Grid.Column;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.Span;
import java.util.List;
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
        grid.addColumn(feed -> agencyNames(feed.getAgencyNamesList())).setHeader(grid.getTranslation("column.agency"));
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader(grid.getTranslation("column.sha256"));
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes())).setHeader(grid.getTranslation("column.size"));
        grid.addColumn(StaticFeed::getObjectKey).setHeader(grid.getTranslation("column.objectKey"));
        grid.addComponentColumn(UiComponents::sourceLink).setHeader(grid.getTranslation("column.source"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getIngestedAt()))
                .setHeader(grid.getTranslation("column.ingested"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader(grid.getTranslation("column.loaded"));
    }

    /** All agencies a feed describes, in agency order; a feed with none shows the placeholder. */
    private static String agencyNames(List<String> names) {
        return names.isEmpty() ? UiFormat.NOT_AVAILABLE : String.join(", ", names);
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
        grid.addColumn(stop -> stop.hasLat() ? UiFormat.coordinate(stop.getLat()) : UiFormat.NOT_AVAILABLE)
                .setHeader(grid.getTranslation("column.latitude"));
        grid.addColumn(stop -> stop.hasLon() ? UiFormat.coordinate(stop.getLon()) : UiFormat.NOT_AVAILABLE)
                .setHeader(grid.getTranslation("column.longitude"));
        grid.addColumn(stop -> stop.hasLocationType() ? String.valueOf(stop.getLocationType()) : UiFormat.NOT_AVAILABLE)
                .setHeader(grid.getTranslation("column.locationType"))
                .setKey("locationType")
                .setSortProperty("locationType")
                .setSortable(true);
        grid.addColumn(stop -> stop.hasParentStation() ? stop.getParentStation() : UiFormat.NOT_AVAILABLE)
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
        grid.addColumn(trip -> trip.hasDirectionId() ? String.valueOf(trip.getDirectionId()) : UiFormat.NOT_AVAILABLE)
                .setHeader(grid.getTranslation("column.direction"));
    }

    /**
     * Columns of the live trip table.
     *
     * <p>The first column is the trip label: its static short name when the feed publishes one,
     * falling back to the trip id. The stop timeline and map live in the detail pane that opens
     * when a row is selected, so the table stays a scannable one-row-per-trip overview.
     */
    public static void liveUpdates(Grid<TripUpdate> grid) {
        grid.addComponentColumn(trip -> label(tripName(trip)))
                .setHeader(grid.getTranslation("column.trip"))
                .setKey("tripId")
                .setSortProperty("tripId")
                .setSortable(true);

        grid.addComponentColumn(trip -> UiComponents.badge(trip.getFeedId()))
                .setHeader(grid.getTranslation("column.feed"))
                .setKey("feed");

        grid.addColumn(trip -> UiFormat.text(trip.getAgencyName()))
                .setHeader(grid.getTranslation("column.agency"))
                .setKey("agency");

        grid.addComponentColumn(trip -> label(routeName(trip)))
                .setHeader(grid.getTranslation("column.route"))
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);

        grid.addColumn(trip -> trip.hasRouteType() ? routeTypeLabel(grid, trip.getRouteType()) : "")
                .setHeader(grid.getTranslation("column.routeType"))
                .setKey("routeType");

        grid.addColumn(trip -> UiFormat.text(trip.getStartDate()))
                .setHeader(grid.getTranslation("column.startDate"))
                .setKey("startDate");

        grid.addColumn(trip -> UiFormat.time(trip.getStartTime()))
                .setHeader(grid.getTranslation("column.startTime"))
                .setKey("startTime");

        grid.addColumn(Columns::currentDelay)
                .setHeader(grid.getTranslation("column.currentDelay"))
                .setKey("currentDelay")
                .setPartNameGenerator(trip -> delayPart(trip.hasCurrentDelay() ? trip.getCurrentDelay() : null));

        grid.addColumn(trip -> UiFormat.dateTime(trip.getFeedTs()))
                .setHeader(grid.getTranslation("column.feedTimestamp"))
                .setKey("feedTs")
                .setSortProperty("feedTs")
                .setSortable(true);

        grid.addColumn(trip -> UiFormat.dateTime(trip.getLastSeenAt()))
                .setHeader(grid.getTranslation("column.lastSeen"))
                .setKey("lastSeenAt")
                .setSortProperty("lastSeenAt")
                .setSortable(true);

        groupHeaderRow(grid);
    }

    /**
     * Groups the live table's columns under a spanning header row. Groups must be contiguous:
     * Vaadin joins adjacent header cells, so the column order defines the grouping.
     */
    private static void groupHeaderRow(Grid<TripUpdate> grid) {
        var row = grid.prependHeaderRow();
        join(row, grid, "column.group.trip", "tripId", "feed", "agency", "routeId", "routeType");
        join(row, grid, "column.group.service", "startDate", "startTime");
        join(row, grid, "column.group.status", "currentDelay", "feedTs", "lastSeenAt");
    }

    /**
     * Translated GTFS route type for display. Extended route types have no label in the bundle and
     * fall back to the numeric code.
     */
    public static String routeTypeLabel(Component owner, int routeType) {
        return Filters.ROUTE_TYPES.contains(routeType)
                ? owner.getTranslation("filter.routeType." + routeType)
                : String.valueOf(routeType);
    }

    private static void join(HeaderRow row, Grid<TripUpdate> grid, String groupKey, String... columnKeys) {
        Column<?>[] columns =
                java.util.Arrays.stream(columnKeys).map(grid::getColumnByKey).toArray(Column<?>[]::new);
        row.join(columns).setText(grid.getTranslation(groupKey));
    }

    /**
     * Label cell that keeps the column narrow: long ids are shortened, with the full value available
     * as a tooltip on hover.
     */
    private static Span label(String value) {
        return label(value, value);
    }

    private static Span label(String value, String title) {
        var span = new Span(UiFormat.elideId(value));
        span.setTitle(UiFormat.text(title));
        return span;
    }

    /**
     * Stop label, preferring the static schedule's stop name over the raw stop id.
     *
     * <p>A stop time update only carries a stop id; the name is resolved from the static schedule
     * and is absent when no static stop matches. The stop id stays available as the tooltip.
     */
    static String stopName(StopTimeUpdate stop) {
        return stop.hasStopName() ? stop.getStopName() : UiFormat.text(stopId(stop));
    }

    /**
     * Trip label, preferring the static schedule's short name over the raw trip id.
     *
     * <p>GTFS makes {@code trip_short_name} optional, so most feeds fall back to the id; the live
     * grid shows the name when the feed publishes one.
     */
    static String tripName(TripUpdate trip) {
        return trip.hasTripShortName() ? trip.getTripShortName() : trip.getTripId();
    }

    /**
     * Route label, preferring the static schedule's long name, then its short name, then the id.
     *
     * <p>GTFS requires at least one of the two names, but a feed may publish only one, and a live
     * trip whose static trip is unknown has neither.
     */
    static String routeName(TripUpdate trip) {
        if (trip.hasRouteLongName()) {
            return trip.getRouteLongName();
        }
        if (trip.hasRouteShortName()) {
            return trip.getRouteShortName();
        }
        return trip.getRouteId();
    }

    private static String stopId(StopTimeUpdate stop) {
        if (stop.hasStopId()) {
            return stop.getStopId();
        }
        return stop.hasAssignedStopId() ? stop.getAssignedStopId() : null;
    }

    private static String currentDelay(TripUpdate trip) {
        return UiFormat.delay(trip.hasCurrentDelay() ? trip.getCurrentDelay() : null);
    }

    /**
     * Part name that colors a delay: blue when early, green when on time, orange when late, and
     * red with extra weight once the delay reaches five minutes. Shared with the trip timeline so
     * both views read the same way.
     */
    static String delayPart(Integer seconds) {
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
}
