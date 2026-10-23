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
            String agencyId,
            String agencyName,
            String agencyUrl,
            String agencyTimezone,
            String agencyLang,
            String agencyFareUrl,
            String agencyEmail,
            String agencyPhone,
            String cemvSupport) {
        static AgencyRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new AgencyRow(
                    columns.value(record, "agency_id"),
                    columns.value(record, "agency_name"),
                    columns.value(record, "agency_url"),
                    columns.value(record, "agency_timezone"),
                    columns.value(record, "agency_lang"),
                    columns.value(record, "agency_fare_url"),
                    columns.value(record, "agency_email"),
                    columns.value(record, "agency_phone"),
                    columns.value(record, "cemv_support"));
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
            String wheelchairBoarding,
            String stopCode,
            String ttsStopName,
            String stopDesc,
            String zoneId,
            String levelId,
            String platformCode,
            String stopAccess) {
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
                    columns.value(record, "wheelchair_boarding"),
                    columns.value(record, "stop_code"),
                    columns.value(record, "tts_stop_name"),
                    columns.value(record, "stop_desc"),
                    columns.value(record, "zone_id"),
                    columns.value(record, "level_id"),
                    columns.value(record, "platform_code"),
                    columns.value(record, "stop_access"));
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
            String continuousDropOff,
            String agencyId,
            String routeDesc,
            String networkId,
            String cemvSupport) {
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
                    columns.value(record, "continuous_drop_off"),
                    columns.value(record, "agency_id"),
                    columns.value(record, "route_desc"),
                    columns.value(record, "network_id"),
                    columns.value(record, "cemv_support"));
        }
    }

    public record TripRow(
            String routeId,
            String serviceId,
            String tripId,
            String directionId,
            String wheelchairAccessible,
            String bikesAllowed,
            String tripHeadsign,
            String tripShortName,
            String blockId,
            String shapeId,
            String carsAllowed,
            String safeDurationFactor,
            String safeDurationOffset) {
        static TripRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new TripRow(
                    columns.value(record, "route_id"),
                    columns.value(record, "service_id"),
                    columns.value(record, "trip_id"),
                    columns.value(record, "direction_id"),
                    columns.value(record, "wheelchair_accessible"),
                    columns.value(record, "bikes_allowed"),
                    columns.value(record, "trip_headsign"),
                    columns.value(record, "trip_short_name"),
                    columns.value(record, "block_id"),
                    columns.value(record, "shape_id"),
                    columns.value(record, "cars_allowed"),
                    columns.value(record, "safe_duration_factor"),
                    columns.value(record, "safe_duration_offset"));
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
            String timepoint,
            String locationGroupId,
            String locationId,
            String stopHeadsign,
            String startPickupDropOffWindow,
            String endPickupDropOffWindow,
            String continuousPickup,
            String continuousDropOff,
            String pickupBookingRuleId,
            String dropOffBookingRuleId) {
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
                    columns.value(record, "timepoint"),
                    columns.value(record, "location_group_id"),
                    columns.value(record, "location_id"),
                    columns.value(record, "stop_headsign"),
                    columns.value(record, "start_pickup_drop_off_window"),
                    columns.value(record, "end_pickup_drop_off_window"),
                    columns.value(record, "continuous_pickup"),
                    columns.value(record, "continuous_drop_off"),
                    columns.value(record, "pickup_booking_rule_id"),
                    columns.value(record, "drop_off_booking_rule_id"));
        }
    }

    /**
     * Weekly service pattern of one {@code service_id}.
     *
     * <p>The validator only needs the {@code service_id} of this file to answer referential
     * questions, but persistence needs the pattern itself, so the whole row is mapped here.
     */
    public record CalendarRow(
            String serviceId,
            String monday,
            String tuesday,
            String wednesday,
            String thursday,
            String friday,
            String saturday,
            String sunday,
            String startDate,
            String endDate) {
        static CalendarRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new CalendarRow(
                    columns.value(record, "service_id"),
                    columns.value(record, "monday"),
                    columns.value(record, "tuesday"),
                    columns.value(record, "wednesday"),
                    columns.value(record, "thursday"),
                    columns.value(record, "friday"),
                    columns.value(record, "saturday"),
                    columns.value(record, "sunday"),
                    columns.value(record, "start_date"),
                    columns.value(record, "end_date"));
        }
    }

    /** One added or removed service date of a {@code service_id}. */
    public record CalendarDateRow(String serviceId, String date, String exceptionType) {
        static CalendarDateRow from(NamedCsvRecord record, GtfsStaticColumns columns) {
            return new CalendarDateRow(
                    columns.value(record, "service_id"),
                    columns.value(record, "date"),
                    columns.value(record, "exception_type"));
        }
    }
}
