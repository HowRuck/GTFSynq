package org.example.gtfsynq.ui.component;

import com.vaadin.flow.component.grid.Grid;
import org.example.gtfsynq.shared.protocol.query.Route;
import org.example.gtfsynq.shared.protocol.query.StaticFeed;
import org.example.gtfsynq.shared.protocol.query.Stop;
import org.example.gtfsynq.shared.protocol.query.Trip;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;
import org.example.gtfsynq.ui.format.UiFormat;

/**
 * Column definitions for the console tables.
 *
 * <p>Column keys double as the backend sort keys, so they must match the sort maps in
 * {@code GtfsQueryRepository}. Headers are translated through the grid, so they follow the locale
 * of the view that built it.
 */
public final class Columns {

    private Columns() {}

    public static void feedSummary(Grid<StaticFeed> grid) {
        grid.addComponentColumn(feed -> UiComponents.badge(feed.getFeedId()))
                .setHeader(grid.getTranslation("column.feed"))
                .setFlexGrow(0)
                .setWidth("9rem");
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader(grid.getTranslation("column.sha256"));
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes()))
                .setHeader(grid.getTranslation("column.size"))
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader(grid.getTranslation("column.loaded"));
    }

    public static void feeds(Grid<StaticFeed> grid) {
        grid.addComponentColumn(feed -> UiComponents.badge(feed.getFeedId()))
                .setHeader(grid.getTranslation("column.feed"))
                .setFlexGrow(0)
                .setWidth("9rem");
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader(grid.getTranslation("column.sha256"));
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes()))
                .setHeader(grid.getTranslation("column.size"))
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(StaticFeed::getObjectKey).setHeader(grid.getTranslation("column.objectKey"));
        grid.addComponentColumn(UiComponents::sourceLink).setHeader(grid.getTranslation("column.source"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getIngestedAt()))
                .setHeader(grid.getTranslation("column.ingested"));
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader(grid.getTranslation("column.loaded"));
    }

    public static void routes(Grid<Route> grid) {
        grid.addColumn(Route::getFeedId)
                .setHeader(grid.getTranslation("column.feed"))
                .setFlexGrow(0)
                .setWidth("9rem");
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
                .setSortable(true)
                .setFlexGrow(0)
                .setWidth("5rem");
        grid.addComponentColumn(UiComponents::routeColor).setHeader(grid.getTranslation("column.color"));
    }

    public static void stops(Grid<Stop> grid) {
        grid.addColumn(Stop::getFeedId)
                .setHeader(grid.getTranslation("column.feed"))
                .setFlexGrow(0)
                .setWidth("9rem");
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
                .setHeader(grid.getTranslation("column.latitude"))
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(stop -> stop.hasLon() ? UiFormat.coordinate(stop.getLon()) : "—")
                .setHeader(grid.getTranslation("column.longitude"))
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(stop -> stop.hasLocationType() ? String.valueOf(stop.getLocationType()) : "—")
                .setHeader(grid.getTranslation("column.locationType"))
                .setKey("locationType")
                .setSortProperty("locationType")
                .setSortable(true)
                .setFlexGrow(0)
                .setWidth("8rem");
        grid.addColumn(stop -> stop.hasParentStation() ? stop.getParentStation() : "—")
                .setHeader(grid.getTranslation("column.parentStation"));
    }

    public static void trips(Grid<Trip> grid) {
        grid.addColumn(Trip::getFeedId)
                .setHeader(grid.getTranslation("column.feed"))
                .setFlexGrow(0)
                .setWidth("9rem");
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
                .setHeader(grid.getTranslation("column.direction"))
                .setFlexGrow(0)
                .setWidth("6rem");
    }

    public static void tripUpdates(Grid<TripUpdate> grid) {
        grid.addColumn(TripUpdate::getFeedId)
                .setHeader(grid.getTranslation("column.feed"))
                .setFlexGrow(0)
                .setWidth("9rem");
        grid.addColumn(update -> update.hasTripId() ? update.getTripId() : "—")
                .setHeader(grid.getTranslation("column.tripId"))
                .setKey("tripId")
                .setSortProperty("tripId")
                .setSortable(true);
        grid.addColumn(update -> update.hasRouteId() ? update.getRouteId() : "—")
                .setHeader(grid.getTranslation("column.routeId"))
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);
        grid.addColumn(update -> update.hasStartDate() ? update.getStartDate() : "—")
                .setHeader(grid.getTranslation("column.startDate"));
        grid.addColumn(update -> UiFormat.time(update.hasStartTime() ? update.getStartTime() : null))
                .setHeader(grid.getTranslation("column.startTime"));
        grid.addColumn(update -> UiFormat.dateTime(update.getFeedTs()))
                .setHeader(grid.getTranslation("column.feedTimestamp"))
                .setKey("feedTs")
                .setSortProperty("feedTs")
                .setSortable(true);
        grid.addColumn(update -> UiFormat.dateTime(update.getLastSeenAt()))
                .setHeader(grid.getTranslation("column.lastSeen"))
                .setKey("lastSeenAt")
                .setSortProperty("lastSeenAt")
                .setSortable(true);
    }
}
