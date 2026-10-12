package org.example.gtfsynq.shared.validation;

import java.util.List;

/**
 * The GTFS static files the validator understands, each carrying the metadata its checks need.
 *
 * <p>Keeping the file identity in one enum keeps the pass order, the required columns and the
 * referential role from drifting apart across parallel collections, and lets the row dispatch be an
 * exhaustive switch instead of a string comparison with a catch-all branch.
 */
enum GtfsStaticFile {

    /** Validated first, because later files reference these ids. */
    AGENCY("agency.txt", Kind.DIMENSION, List.of("agency_name", "agency_url", "agency_timezone")),
    STOPS("stops.txt", Kind.DIMENSION, List.of("stop_id", "stop_name", "stop_lat", "stop_lon")),
    ROUTES("routes.txt", Kind.DIMENSION, List.of("route_id", "route_type")),
    TRIPS("trips.txt", Kind.DIMENSION, List.of("route_id", "service_id", "trip_id")),

    /** Ordered by {@code stop_sequence} per trip, so it gets its own ordering check. */
    STOP_TIMES("stop_times.txt", Kind.STOP_TIMES, List.of("trip_id", "stop_id", "stop_sequence")),

    /**
     * Service calendars only feed the service_id set; rows are not otherwise validated. At least one
     * of the pair must be present.
     */
    CALENDAR("calendar.txt", Kind.SERVICE, List.of()),
    CALENDAR_DATES("calendar_dates.txt", Kind.SERVICE, List.of());

    /** What the file's rows contribute. */
    enum Kind {
        DIMENSION,
        STOP_TIMES,
        SERVICE
    }

    private final String fileName;

    private final Kind kind;

    private final List<String> requiredColumns;

    GtfsStaticFile(String fileName, Kind kind, List<String> requiredColumns) {
        this.fileName = fileName;
        this.kind = kind;
        this.requiredColumns = requiredColumns;
    }

    /** Name of the entry inside the archive, e.g. {@code stop_times.txt}. */
    String fileName() {
        return fileName;
    }

    Kind kind() {
        return kind;
    }

    /** Columns whose absence makes the file's checks impossible; empty when none are required. */
    List<String> requiredColumns() {
        return requiredColumns;
    }

    /** Whether checks on this file depend on the id indexes, and so degrade when they truncate. */
    boolean idDependent() {
        return this != AGENCY;
    }
}
