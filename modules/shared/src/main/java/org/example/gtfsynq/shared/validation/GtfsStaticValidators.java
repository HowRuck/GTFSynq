package org.example.gtfsynq.shared.validation;

import am.ik.yavi.builder.ValidatorBuilder;
import am.ik.yavi.constraint.CharSequenceConstraint;
import am.ik.yavi.core.Validator;
import java.net.URI;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.AgencyRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.RouteRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.TripRow;

/**
 * Row-local YAVI validators for the validated GTFS static files.
 *
 * <p>Rules follow the GTFS Schedule Reference for presence, type and range (the errors a feed
 * cannot legally ship), cross-checked against the MobilityData canonical validator's {@code
 * invalid_*}, {@code missing_required_*} and {@code duplicate_key} notices. Enumeration members
 * that the reference marks optional-but-closed are enforced; purely cosmetic notices are not.
 *
 * <p>Validators are static and thread-safe, allocate nothing per row, and are built once at class
 * initialization. YAVI accumulates violations rather than failing fast, so every predicate here is
 * null-safe: an absent cell must be rejected by the predicate, not throw.
 */
public final class GtfsStaticValidators {

    private GtfsStaticValidators() {}

    private static final Pattern HEX_COLOR = Pattern.compile("[0-9A-Fa-f]{6}");

    private static final Pattern GTFS_TIME = Pattern.compile("\\d+:[0-5]\\d:[0-5]\\d");

    private static final Pattern LANGUAGE_TAG = Pattern.compile("[a-zA-Z]{2,3}(-[a-zA-Z0-9]+)*");

    private static final Set<String> LOCATION_TYPES = Set.of("0", "1", "2", "3", "4");

    private static final Set<String> WHEELCHAIR_BOARDING = Set.of("0", "1", "2");

    private static final Set<String> ROUTE_TYPES = Set.of("0", "1", "2", "3", "4", "5", "6", "7", "11", "12");

    private static final Set<String> CONTINUOUS_PICKUP_DROP_OFF = Set.of("0", "1", "2", "3");

    private static final Set<String> DIRECTION_IDS = Set.of("0", "1");

    private static final Set<String> WHEELCHAIR_ACCESSIBLE = Set.of("0", "1", "2");

    private static final Set<String> BIKES_ALLOWED = Set.of("0", "1", "2");

    private static final Set<String> PICKUP_DROP_OFF_TYPES = Set.of("0", "1", "2", "3");

    private static final Set<String> TIMEPOINTS = Set.of("0", "1");

    private static final String TIMEZONE_MESSAGE = "\"{0}\" must be a valid IANA timezone";

    private static final String LANGUAGE_MESSAGE = "\"{0}\" must be a BCP 47 language code";

    private static final String URL_KEY = "gtfs.url";

    private static final String URL_MESSAGE = "\"{0}\" must be a valid URL";

    private static final String COLOR_MESSAGE = "\"{0}\" must be a six-digit hex color";

    private static final String TIME_MESSAGE = "\"{0}\" must match H:MM:SS (hours may exceed 24)";

    static final Validator<AgencyRow> AGENCY = ValidatorBuilder.<AgencyRow>of()
            ._string(AgencyRow::agencyName, "agency_name", c -> c.notBlank())
            ._string(AgencyRow::agencyUrl, "agency_url", c -> c.notBlank().url())
            ._string(
                    AgencyRow::agencyTimezone,
                    "agency_timezone",
                    c -> c.notBlank().predicate(GtfsStaticValidators::isTimezone, "gtfs.timezone", TIMEZONE_MESSAGE))
            ._string(
                    AgencyRow::agencyLang,
                    "agency_lang",
                    c -> whenPresent(c, v -> LANGUAGE_TAG.matcher(v).matches(), "gtfs.language", LANGUAGE_MESSAGE))
            ._string(
                    AgencyRow::agencyFareUrl,
                    "agency_fare_url",
                    c -> whenPresent(c, GtfsStaticValidators::isUrl, URL_KEY, URL_MESSAGE))
            ._string(
                    AgencyRow::agencyEmail,
                    "agency_email",
                    c -> whenPresent(c, GtfsStaticValidators::isEmail, "gtfs.email", "\"{0}\" must be a valid email"))
            .build();

    private static final Set<String> LOCATION_TYPES_REQUIRING_PARENT = Set.of("2", "3", "4");

    static final Validator<StopRow> STOP = ValidatorBuilder.<StopRow>of()
            ._string(StopRow::stopId, "stop_id", c -> c.notBlank())
            ._string(StopRow::stopName, "stop_name", c -> c.notBlank())
            ._string(
                    StopRow::stopLat,
                    "stop_lat",
                    c -> whenPresent(
                            c,
                            v -> isWithin(v, -90.0, 90.0),
                            "gtfs.latitude",
                            "\"{0}\" must be a number between -90 and 90"))
            ._string(
                    StopRow::stopLon,
                    "stop_lon",
                    c -> whenPresent(
                            c,
                            v -> isWithin(v, -180.0, 180.0),
                            "gtfs.longitude",
                            "\"{0}\" must be a number between -180 and 180"))
            ._string(
                    StopRow::stopUrl,
                    "stop_url",
                    c -> whenPresent(c, GtfsStaticValidators::isUrl, URL_KEY, URL_MESSAGE))
            ._string(StopRow::locationType, "location_type", c -> oneOf(c, LOCATION_TYPES))
            ._string(
                    StopRow::stopTimezone,
                    "stop_timezone",
                    c -> whenPresent(c, GtfsStaticValidators::isTimezone, "gtfs.timezone", TIMEZONE_MESSAGE))
            ._string(StopRow::wheelchairBoarding, "wheelchair_boarding", c -> oneOf(c, WHEELCHAIR_BOARDING))
            .constraintOnTarget(
                    // parent_station is required for entrances (2), generic nodes (3) and boarding
                    // areas (4); stops (0) and stations (1) must not have one.
                    // Mirrors gtfs-validator location_without_parent_station.
                    r -> r.locationType() == null
                            || !LOCATION_TYPES_REQUIRING_PARENT.contains(r.locationType())
                            || r.parentStation() != null,
                    "parent_station",
                    "gtfs.parentStation",
                    "\"parent_station\" is required when location_type is 2, 3 or 4")
            .build();

    static final Validator<RouteRow> ROUTE = ValidatorBuilder.<RouteRow>of()
            ._string(RouteRow::routeId, "route_id", c -> c.notBlank())
            ._string(RouteRow::routeType, "route_type", c -> oneOf(c.notBlank(), ROUTE_TYPES))
            ._string(
                    RouteRow::routeUrl,
                    "route_url",
                    c -> whenPresent(c, GtfsStaticValidators::isUrl, URL_KEY, URL_MESSAGE))
            ._string(
                    RouteRow::routeColor,
                    "route_color",
                    c -> whenPresent(c, v -> HEX_COLOR.matcher(v).matches(), "gtfs.color", COLOR_MESSAGE))
            ._string(
                    RouteRow::routeTextColor,
                    "route_text_color",
                    c -> whenPresent(c, v -> HEX_COLOR.matcher(v).matches(), "gtfs.color", COLOR_MESSAGE))
            ._string(
                    RouteRow::routeSortOrder,
                    "route_sort_order",
                    c -> whenPresent(
                            c,
                            GtfsStaticValidators::isNonNegativeInt,
                            "gtfs.sortOrder",
                            "\"{0}\" must be a non-negative integer"))
            ._string(RouteRow::continuousPickup, "continuous_pickup", c -> oneOf(c, CONTINUOUS_PICKUP_DROP_OFF))
            ._string(RouteRow::continuousDropOff, "continuous_drop_off", c -> oneOf(c, CONTINUOUS_PICKUP_DROP_OFF))
            .constraintOnTarget(
                    // Mirrors gtfs-validator route_short_and_long_names: at least one name is required.
                    r -> r.routeShortName() != null || r.routeLongName() != null,
                    "route_short_name/route_long_name",
                    "gtfs.routeName",
                    "one of \"route_short_name\" or \"route_long_name\" must be present")
            .build();

    static final Validator<TripRow> TRIP = ValidatorBuilder.<TripRow>of()
            ._string(TripRow::routeId, "route_id", c -> c.notBlank())
            ._string(TripRow::serviceId, "service_id", c -> c.notBlank())
            ._string(TripRow::tripId, "trip_id", c -> c.notBlank())
            ._string(TripRow::directionId, "direction_id", c -> oneOf(c, DIRECTION_IDS))
            ._string(TripRow::wheelchairAccessible, "wheelchair_accessible", c -> oneOf(c, WHEELCHAIR_ACCESSIBLE))
            ._string(TripRow::bikesAllowed, "bikes_allowed", c -> oneOf(c, BIKES_ALLOWED))
            .build();

    static final Validator<StopTimeRow> STOP_TIME = ValidatorBuilder.<StopTimeRow>of()
            ._string(StopTimeRow::tripId, "trip_id", c -> c.notBlank())
            ._string(StopTimeRow::stopId, "stop_id", c -> c.notBlank())
            ._string(
                    StopTimeRow::stopSequence,
                    "stop_sequence",
                    c -> c.notBlank()
                            .predicate(
                                    v -> isNonNegativeInt(v),
                                    "gtfs.stopSequence",
                                    "\"{0}\" must be a non-negative integer"))
            ._string(
                    StopTimeRow::arrivalTime,
                    "arrival_time",
                    c -> whenPresent(c, v -> GTFS_TIME.matcher(v).matches(), "gtfs.time", TIME_MESSAGE))
            ._string(
                    StopTimeRow::departureTime,
                    "departure_time",
                    c -> whenPresent(c, v -> GTFS_TIME.matcher(v).matches(), "gtfs.time", TIME_MESSAGE))
            ._string(StopTimeRow::pickupType, "pickup_type", c -> oneOf(c, PICKUP_DROP_OFF_TYPES))
            ._string(StopTimeRow::dropOffType, "drop_off_type", c -> oneOf(c, PICKUP_DROP_OFF_TYPES))
            ._string(
                    StopTimeRow::shapeDistTraveled,
                    "shape_dist_traveled",
                    c -> whenPresent(
                            c,
                            v -> isWithin(v, 0.0, Double.MAX_VALUE),
                            "gtfs.shapeDist",
                            "\"{0}\" must be a non-negative number"))
            ._string(StopTimeRow::timepoint, "timepoint", c -> oneOf(c, TIMEPOINTS))
            .build();

    /**
     * Applies a predicate to non-blank cells only, so an absent optional field is valid.
     *
     * <p>Every predicate passed here must reject the empty string itself; the null guard is the only
     * thing this wrapper adds.
     */
    private static <T> CharSequenceConstraint<T, String> whenPresent(
            CharSequenceConstraint<T, String> constraint,
            Predicate<String> predicate,
            String messageKey,
            String message) {
        return constraint.predicate(v -> v == null || predicate.test(v), messageKey, message);
    }

    /** Constrains a cell to the given closed set of values; absent cells are valid. */
    private static <T> CharSequenceConstraint<T, String> oneOf(
            CharSequenceConstraint<T, String> constraint, Set<String> allowed) {
        return whenPresent(constraint, allowed::contains, "gtfs.enumeration", "\"{0}\" must be one of " + allowed);
    }

    private static boolean isTimezone(String value) {
        try {
            // Resolving the id is what proves the value is a zone; keep the result so the call is
            // not dead code.
            return !ZoneId.of(value).getId().isEmpty();
        } catch (DateTimeException _) {
            return false;
        }
    }

    private static boolean isUrl(String value) {
        try {
            var uri = URI.create(value);
            var scheme = uri.getScheme();
            return uri.getHost() != null
                    && scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private static boolean isEmail(String value) {
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && value.indexOf('@', at + 1) == -1 && value.contains(".");
    }

    private static boolean isWithin(String value, double min, double max) {
        try {
            // NaN fails both comparisons, so the range check already rejects it.
            double parsed = Double.parseDouble(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    private static boolean isNonNegativeInt(String value) {
        try {
            return Integer.parseInt(value) >= 0;
        } catch (NumberFormatException _) {
            return false;
        }
    }
}
