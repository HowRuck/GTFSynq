package org.example.gtfsynq.shared.validation;

import de.siegmar.fastcsv.reader.NamedCsvRecord;

/**
 * Row shapes for the validated GTFS static files.
 *
 * <p>Each record holds the raw cell values a validator or referential check consumes; values are
 * read through a prebuilt {@link GtfsStaticColumns} index, so mapping a row costs one map hit per
 * field rather than a header scan. Optional fields stay {@code null} when absent or blank.
 */
public final class GtfsStaticRows {

    private GtfsStaticRows() {}

    public record AgencyRow(
            String agencyName,
            String agencyUrl,
            String agencyTimezone,
            String agencyLang,
            String agencyFareUrl,
            String agencyEmail) {
        static AgencyRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new AgencyRow(
                    columns.value(record, "agency_name"),
                    columns.value(record, "agency_url"),
                    columns.value(record, "agency_timezone"),
                    columns.value(record, "agency_lang"),
                    columns.value(record, "agency_fare_url"),
                    columns.value(record, "agency_email"));
        }
    }

    public record StopRow(
            String stopId,
            String stopName,
            String stopLat,
            String stopLon,
            String stopUrl,
            String locationType,
            String parentStation,
            String stopTimezone,
            String wheelchairBoarding) {
        static StopRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new StopRow(
                    columns.value(record, "stop_id"),
                    columns.value(record, "stop_name"),
                    columns.value(record, "stop_lat"),
                    columns.value(record, "stop_lon"),
                    columns.value(record, "stop_url"),
                    columns.value(record, "location_type"),
                    columns.value(record, "parent_station"),
                    columns.value(record, "stop_timezone"),
                    columns.value(record, "wheelchair_boarding"));
        }
    }

    public record RouteRow(
            String routeId,
            String routeShortName,
            String routeLongName,
            String routeType,
            String routeUrl,
            String routeColor,
            String routeTextColor,
            String routeSortOrder,
            String continuousPickup,
            String continuousDropOff) {
        static RouteRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new RouteRow(
                    columns.value(record, "route_id"),
                    columns.value(record, "route_short_name"),
                    columns.value(record, "route_long_name"),
                    columns.value(record, "route_type"),
                    columns.value(record, "route_url"),
                    columns.value(record, "route_color"),
                    columns.value(record, "route_text_color"),
                    columns.value(record, "route_sort_order"),
                    columns.value(record, "continuous_pickup"),
                    columns.value(record, "continuous_drop_off"));
        }
    }

    public record TripRow(
            String routeId,
            String serviceId,
            String tripId,
            String directionId,
            String wheelchairAccessible,
            String bikesAllowed) {
        static TripRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new TripRow(
                    columns.value(record, "route_id"),
                    columns.value(record, "service_id"),
                    columns.value(record, "trip_id"),
                    columns.value(record, "direction_id"),
                    columns.value(record, "wheelchair_accessible"),
                    columns.value(record, "bikes_allowed"));
        }
    }

    public record StopTimeRow(
            String tripId,
            String arrivalTime,
            String departureTime,
            String stopId,
            String stopSequence,
            String pickupType,
            String dropOffType,
            String shapeDistTraveled,
            String timepoint) {
        static StopTimeRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new StopTimeRow(
                    columns.value(record, "trip_id"),
                    columns.value(record, "arrival_time"),
                    columns.value(record, "departure_time"),
                    columns.value(record, "stop_id"),
                    columns.value(record, "stop_sequence"),
                    columns.value(record, "pickup_type"),
                    columns.value(record, "drop_off_type"),
                    columns.value(record, "shape_dist_traveled"),
                    columns.value(record, "timepoint"));
        }
    }
}
