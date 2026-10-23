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
 * {@code GtfsQueryRepository}.
 */
public final class Columns {

    private Columns() {}

    public static void feedSummary(Grid<StaticFeed> grid) {
        grid.addComponentColumn(feed -> UiComponents.badge(feed.getFeedId()))
                .setHeader("Feed")
                .setFlexGrow(0)
                .setWidth("9rem");
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader("SHA-256");
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes()))
                .setHeader("Size")
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader("Loaded");
    }

    public static void feeds(Grid<StaticFeed> grid) {
        grid.addComponentColumn(feed -> UiComponents.badge(feed.getFeedId()))
                .setHeader("Feed")
                .setFlexGrow(0)
                .setWidth("9rem");
        grid.addColumn(feed -> UiFormat.shortHash(feed.getSha256())).setHeader("SHA-256");
        grid.addColumn(feed -> UiFormat.bytes(feed.getSizeBytes()))
                .setHeader("Size")
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(StaticFeed::getObjectKey).setHeader("Object key");
        grid.addComponentColumn(UiComponents::sourceLink).setHeader("Source");
        grid.addColumn(feed -> UiFormat.dateTime(feed.getIngestedAt())).setHeader("Ingested");
        grid.addColumn(feed -> UiFormat.dateTime(feed.getLoadedAt())).setHeader("Loaded");
    }

    public static void routes(Grid<Route> grid) {
        grid.addColumn(Route::getFeedId).setHeader("Feed").setFlexGrow(0).setWidth("9rem");
        grid.addColumn(Route::getRouteId)
                .setHeader("Route ID")
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);
        grid.addColumn(route -> route.hasShortName() ? route.getShortName() : "")
                .setHeader("Short name")
                .setKey("shortName")
                .setSortProperty("shortName")
                .setSortable(true);
        grid.addColumn(route -> route.hasLongName() ? route.getLongName() : "")
                .setHeader("Long name")
                .setKey("longName")
                .setSortProperty("longName")
                .setSortable(true);
        grid.addColumn(Route::getRouteType)
                .setHeader("Type")
                .setKey("routeType")
                .setSortProperty("routeType")
                .setSortable(true)
                .setFlexGrow(0)
                .setWidth("5rem");
        grid.addComponentColumn(UiComponents::colorSwatch).setHeader("Color");
    }

    public static void stops(Grid<Stop> grid) {
        grid.addColumn(Stop::getFeedId).setHeader("Feed").setFlexGrow(0).setWidth("9rem");
        grid.addColumn(Stop::getStopId)
                .setHeader("Stop ID")
                .setKey("stopId")
                .setSortProperty("stopId")
                .setSortable(true);
        grid.addColumn(Stop::getStopName)
                .setHeader("Name")
                .setKey("stopName")
                .setSortProperty("stopName")
                .setSortable(true);
        grid.addColumn(stop -> stop.hasLat() ? UiFormat.coordinate(stop.getLat()) : "—")
                .setHeader("Latitude")
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(stop -> stop.hasLon() ? UiFormat.coordinate(stop.getLon()) : "—")
                .setHeader("Longitude")
                .setFlexGrow(0)
                .setWidth("7rem");
        grid.addColumn(stop -> stop.hasLocationType() ? String.valueOf(stop.getLocationType()) : "—")
                .setHeader("Location type")
                .setKey("locationType")
                .setSortProperty("locationType")
                .setSortable(true)
                .setFlexGrow(0)
                .setWidth("8rem");
        grid.addColumn(stop -> stop.hasParentStation() ? stop.getParentStation() : "—")
                .setHeader("Parent station");
    }

    public static void trips(Grid<Trip> grid) {
        grid.addColumn(Trip::getFeedId).setHeader("Feed").setFlexGrow(0).setWidth("9rem");
        grid.addColumn(Trip::getTripId)
                .setHeader("Trip ID")
                .setKey("tripId")
                .setSortProperty("tripId")
                .setSortable(true);
        grid.addColumn(Trip::getRouteId)
                .setHeader("Route ID")
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);
        grid.addColumn(Trip::getServiceId)
                .setHeader("Service ID")
                .setKey("serviceId")
                .setSortProperty("serviceId")
                .setSortable(true);
        grid.addColumn(trip -> trip.hasDirectionId() ? String.valueOf(trip.getDirectionId()) : "—")
                .setHeader("Direction")
                .setFlexGrow(0)
                .setWidth("6rem");
    }

    public static void tripUpdates(Grid<TripUpdate> grid) {
        grid.addColumn(TripUpdate::getFeedId).setHeader("Feed").setFlexGrow(0).setWidth("9rem");
        grid.addColumn(update -> update.hasTripId() ? update.getTripId() : "—")
                .setHeader("Trip ID")
                .setKey("tripId")
                .setSortProperty("tripId")
                .setSortable(true);
        grid.addColumn(update -> update.hasRouteId() ? update.getRouteId() : "—")
                .setHeader("Route ID")
                .setKey("routeId")
                .setSortProperty("routeId")
                .setSortable(true);
        grid.addColumn(update -> update.hasStartDate() ? update.getStartDate() : "—")
                .setHeader("Start date");
        grid.addColumn(update -> UiFormat.time(update.hasStartTime() ? update.getStartTime() : null))
                .setHeader("Start time");
        grid.addColumn(update -> UiFormat.dateTime(update.getFeedTs()))
                .setHeader("Feed timestamp")
                .setKey("feedTs")
                .setSortProperty("feedTs")
                .setSortable(true);
        grid.addColumn(update -> UiFormat.dateTime(update.getLastSeenAt()))
                .setHeader("Last seen")
                .setKey("lastSeenAt")
                .setSortProperty("lastSeenAt")
                .setSortable(true);
    }
}
