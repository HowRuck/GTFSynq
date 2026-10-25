package org.example.gtfsynq.api.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.example.gtfsynq.api.dto.OverviewDto;
import org.example.gtfsynq.api.dto.PageResponse;
import org.example.gtfsynq.api.dto.RouteDto;
import org.example.gtfsynq.api.dto.StaticFeedDto;
import org.example.gtfsynq.api.dto.StopDto;
import org.example.gtfsynq.api.dto.StopTimeUpdateDto;
import org.example.gtfsynq.api.dto.TripDto;
import org.example.gtfsynq.api.dto.TripUpdateDto;

/**
 * Read-only SQL access used by the UI.
 *
 * <p>The queries are intentionally hand-written instead of mapped through Hibernate ORM: the
 * existing schema mixes TimescaleDB hypertables, composite primary keys and PostgreSQL enums, and
 * the UI only needs to list and filter rows.
 */
@ApplicationScoped
public class GtfsQueryRepository {

    private static final int MAX_PAGE_SIZE = 200;

    /** Upper bound that keeps OFFSET pagination bounded (and avoids int overflow). */
    private static final int MAX_PAGE = 100_000;

    private static final int MIN_SEARCH_LENGTH = 3;

    private static final int MAX_SEARCH_LENGTH = 100;

    private static final long OVERVIEW_CACHE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final Map<String, String> ROUTE_SORTS = Map.of(
            "routeId", "route_id",
            "shortName", "route_short_name",
            "longName", "route_long_name",
            "routeType", "route_type");

    private static final Map<String, String> STOP_SORTS =
            Map.of("stopId", "stop_id", "stopName", "stop_name", "locationType", "location_type");

    private static final Map<String, String> TRIP_SORTS =
            Map.of("tripId", "trip_id", "routeId", "route_id", "serviceId", "service_id");

    private static final Map<String, String> LIVE_SORTS = Map.of(
            "lastSeenAt", "h.last_seen_at",
            "feedTs", "h.feed_ts",
            "tripId", "m.trip_id",
            "routeId", "m.route_id");
    private final DataSource dataSource;

    private volatile OverviewDto cachedOverview;

    private volatile long cachedOverviewAt;

    @Inject
    public GtfsQueryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public OverviewDto overview() {
        var now = System.nanoTime();
        var cached = cachedOverview;
        if (cached != null && now - cachedOverviewAt < OVERVIEW_CACHE_NANOS) {
            return cached;
        }

        // Static counts are maintained on static_feeds by the store app, so only the two
        // current-state tables still need a scan here.
        String sql = """
                SELECT (SELECT count(*) FROM static_feeds),
                       (SELECT coalesce(sum(route_count), 0) FROM static_feeds),
                       (SELECT coalesce(sum(stop_count), 0) FROM static_feeds),
                       (SELECT coalesce(sum(trip_count), 0) FROM static_feeds),
                       (SELECT count(*) FROM rt_trip_updates_hot),
                       (SELECT count(*) FROM rt_stop_time_updates_hot)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            var result = new OverviewDto(
                    rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6));
            cachedOverview = result;
            cachedOverviewAt = now;
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load overview", e);
        }
    }

    public List<String> feedIds() {
        // Probe the five enum values instead of scanning the (potentially large) hot table.
        String sql = """
                SELECT e::text
                FROM unnest(enum_range(NULL::feed_id_enum)) AS e
                WHERE EXISTS (SELECT 1 FROM static_feeds f WHERE f.feed_id = e)
                   OR EXISTS (SELECT 1 FROM rt_trip_updates_hot h WHERE h.feed_id = e)
                ORDER BY 1
                """;
        List<String> feedIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                feedIds.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list feeds", e);
        }
        return feedIds;
    }

    public PageResponse<StaticFeedDto> staticFeeds(int page, int size) {
        // A feed may describe several agencies, so they are aggregated into one array beside the
        // feed's row instead of widening the page by one row per agency.
        String select = """
                SELECT f.feed_id::text, f.sha256, f.bucket, f.object_key, f.source_url, f.size_bytes,
                       f.ingested_at, f.loaded_at, a.agency_names
                FROM static_feeds f
                LEFT JOIN LATERAL (
                    SELECT array_agg(agency_name ORDER BY agency_id) AS agency_names
                    FROM static_agency
                    WHERE feed_id = f.feed_id
                ) a ON TRUE
                """;
        String count = "SELECT count(*) FROM static_feeds";
        return page(
                select,
                count,
                List.of(),
                "f.loaded_at DESC, f.feed_id ASC",
                page,
                size,
                rs -> new StaticFeedDto(
                        rs.getString("feed_id"),
                        rs.getString("sha256"),
                        rs.getString("bucket"),
                        rs.getString("object_key"),
                        rs.getString("source_url"),
                        rs.getLong("size_bytes"),
                        offsetDateTime(rs, "ingested_at"),
                        offsetDateTime(rs, "loaded_at"),
                        stringList(rs, "agency_names")));
    }

    /**
     * Agency names available in the static tables, optionally limited to one feed.
     *
     * @param feedId feed filter, or {@code null} for all feeds
     * @return distinct agency names in collation order
     */
    public List<String> agencyNames(String feedId) {
        var filter = hasText(feedId) ? " WHERE feed_id = CAST(? AS feed_id_enum)" : "";
        String sql = "SELECT DISTINCT agency_name FROM static_agency" + filter + " ORDER BY agency_name";
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (hasText(feedId)) {
                statement.setString(1, feedId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list agencies", e);
        }
        return names;
    }

    public PageResponse<RouteDto> routes(
            String feedId, String search, Integer routeType, int page, int size, String sort, String direction) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(feedId)) {
            conditions.add("feed_id = CAST(? AS feed_id_enum)");
            parameters.add(feedId);
        }
        var searchTerm = normalizedSearch(search);
        if (searchTerm != null) {
            conditions.add(
                    "(route_id || ' ' || coalesce(route_short_name, '') || ' ' || coalesce(route_long_name, '')) ILIKE ? ESCAPE '\\'");
            addLike(parameters, searchTerm);
        }
        if (routeType != null) {
            conditions.add("route_type = ?");
            parameters.add(routeType);
        }

        String select = """
                SELECT feed_id::text, route_id, route_short_name, route_long_name, route_type, route_color, route_text_color
                FROM static_routes
                """;
        String count = "SELECT count(*) FROM static_routes";
        String order = sortColumn(ROUTE_SORTS, sort, "route_id") + " " + direction(direction) + ", route_id ASC";
        return page(
                select,
                count,
                conditions,
                parameters,
                order,
                page,
                size,
                rs -> new RouteDto(
                        rs.getString("feed_id"),
                        rs.getString("route_id"),
                        rs.getString("route_short_name"),
                        rs.getString("route_long_name"),
                        rs.getInt("route_type"),
                        rs.getString("route_color"),
                        rs.getString("route_text_color")));
    }

    public PageResponse<StopDto> stops(
            String feedId, String search, Integer locationType, int page, int size, String sort, String direction) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(feedId)) {
            conditions.add("feed_id = CAST(? AS feed_id_enum)");
            parameters.add(feedId);
        }
        var searchTerm = normalizedSearch(search);
        if (searchTerm != null) {
            conditions.add("(stop_id || ' ' || stop_name) ILIKE ? ESCAPE '\\'");
            addLike(parameters, searchTerm);
        }
        if (locationType != null) {
            conditions.add("location_type = ?");
            parameters.add(locationType);
        }

        String select = """
                SELECT feed_id::text, stop_id, stop_name, stop_lat, stop_lon, location_type, parent_station
                FROM static_stops
                """;
        String count = "SELECT count(*) FROM static_stops";
        String order = sortColumn(STOP_SORTS, sort, "stop_name") + " " + direction(direction) + ", stop_id ASC";
        return page(
                select,
                count,
                conditions,
                parameters,
                order,
                page,
                size,
                rs -> new StopDto(
                        rs.getString("feed_id"),
                        rs.getString("stop_id"),
                        rs.getString("stop_name"),
                        nullableDouble(rs, "stop_lat"),
                        nullableDouble(rs, "stop_lon"),
                        nullableInt(rs, "location_type"),
                        rs.getString("parent_station")));
    }

    public PageResponse<TripDto> trips(
            String feedId, String search, Integer directionId, int page, int size, String sort, String direction) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(feedId)) {
            conditions.add("feed_id = CAST(? AS feed_id_enum)");
            parameters.add(feedId);
        }
        var searchTerm = normalizedSearch(search);
        if (searchTerm != null) {
            conditions.add("(trip_id || ' ' || route_id || ' ' || service_id) ILIKE ? ESCAPE '\\'");
            addLike(parameters, searchTerm);
        }
        if (directionId != null) {
            conditions.add("direction_id = ?");
            parameters.add(directionId);
        }

        String select = """
                SELECT feed_id::text, trip_id, route_id, service_id, direction_id
                FROM static_trips
                """;
        String count = "SELECT count(*) FROM static_trips";
        String order = sortColumn(TRIP_SORTS, sort, "trip_id") + " " + direction(direction) + ", trip_id ASC";
        return page(
                select,
                count,
                conditions,
                parameters,
                order,
                page,
                size,
                rs -> new TripDto(
                        rs.getString("feed_id"),
                        rs.getString("trip_id"),
                        rs.getString("route_id"),
                        rs.getString("service_id"),
                        nullableInt(rs, "direction_id")));
    }

    public PageResponse<TripUpdateDto> liveTripUpdates(
            String feedId,
            String search,
            String agency,
            int page,
            int size,
            String sort,
            String direction,
            boolean includeTotal) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(feedId)) {
            conditions.add("h.feed_id = CAST(? AS feed_id_enum)");
            parameters.add(feedId);
        }
        var searchTerm = normalizedSearch(search);
        if (searchTerm != null) {
            conditions.add(
                    "(coalesce(m.entity_id, '') || ' ' || coalesce(m.trip_id, '') || ' ' || coalesce(m.route_id, '')) ILIKE ? ESCAPE '\\'");
            addLike(parameters, searchTerm);
        }
        if (hasText(agency)) {
            conditions.add("a.agency_name = ?");
            parameters.add(agency);
        }

        String select = """
                SELECT h.trip_update_id, h.feed_id::text AS feed_id, h.feed_ts, h.last_seen_at,
                       m.entity_id, m.trip_id, m.route_id, m.start_date, m.start_time,
                       t.trip_short_name, r.route_short_name, r.route_long_name, r.route_type, a.agency_name,
                       (SELECT count(*) FROM rt_stop_time_updates_hot s
                        WHERE s.trip_update_id = h.trip_update_id) AS stop_time_update_count,
                       (SELECT COALESCE(s.arrival_delay, s.departure_delay)
                        FROM rt_stop_time_updates_hot s
                        WHERE s.trip_update_id = h.trip_update_id
                          AND COALESCE(s.arrival_delay, s.departure_delay) IS NOT NULL
                        -- The last stop that reports a delay, not the first: the lowest stop
                        -- sequence is often a stale value for a stop the trip has already passed.
                        ORDER BY s.stop_sequence DESC
                        LIMIT 1) AS current_delay
                FROM rt_trip_updates_hot h
                LEFT JOIN rt_trip_updates_meta m ON m.id = h.trip_update_id
                LEFT JOIN static_trips t ON t.feed_id = h.feed_id AND t.trip_id = m.trip_id
                LEFT JOIN static_routes r
                       ON r.feed_id = h.feed_id AND r.route_id = COALESCE(t.route_id, m.route_id)
                LEFT JOIN static_agency a
                       ON a.feed_id = h.feed_id AND a.agency_id = COALESCE(r.agency_id, '')
                """;
        // The agency condition reaches static_agency through the trip and route joins; only widen
        // the count query when that filter is actually in use.
        String count = hasText(agency) ? """
                SELECT count(*) FROM rt_trip_updates_hot h
                LEFT JOIN rt_trip_updates_meta m ON m.id = h.trip_update_id
                LEFT JOIN static_trips t ON t.feed_id = h.feed_id AND t.trip_id = m.trip_id
                LEFT JOIN static_routes r
                       ON r.feed_id = h.feed_id AND r.route_id = COALESCE(t.route_id, m.route_id)
                LEFT JOIN static_agency a
                       ON a.feed_id = h.feed_id AND a.agency_id = COALESCE(r.agency_id, '')
                """ : """
                SELECT count(*) FROM rt_trip_updates_hot h
                LEFT JOIN rt_trip_updates_meta m ON m.id = h.trip_update_id
                """;
        String order =
                sortColumn(LIVE_SORTS, sort, "h.last_seen_at") + " " + direction(direction) + ", h.trip_update_id DESC";
        return page(
                select,
                count,
                conditions,
                parameters,
                order,
                page,
                size,
                rs -> new TripUpdateDto(
                        rs.getLong("trip_update_id"),
                        rs.getString("feed_id"),
                        offsetDateTime(rs, "feed_ts"),
                        offsetDateTime(rs, "last_seen_at"),
                        rs.getString("entity_id"),
                        rs.getString("trip_id"),
                        rs.getString("route_id"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getInt("stop_time_update_count"),
                        nullableInt(rs, "current_delay"),
                        rs.getString("trip_short_name"),
                        rs.getString("route_short_name"),
                        rs.getString("route_long_name"),
                        rs.getString("agency_name"),
                        nullableInt(rs, "route_type")),
                includeTotal);
    }

    /**
     * Stops of one live trip: the whole static schedule with the live stop time updates layered on
     * top. Both sides are fetched with simple index lookups and merged in Java, so the SQL cannot
     * fan out across stop sequences.
     */
    public List<StopTimeUpdateDto> liveStopTimeUpdates(long tripUpdateId) {
        return TripStopMerge.merge(scheduledStops(tripUpdateId), liveStopTimes(tripUpdateId));
    }

    /** Live stop time updates of one trip, with static stop names resolved. */
    private List<StopTimeUpdateDto> liveStopTimes(long tripUpdateId) {
        String sql = """
                SELECT s.trip_update_id, s.stop_sequence, s.stop_id,
                       s.arrival_time, s.arrival_delay, s.scheduled_arrival_time,
                       s.departure_time, s.departure_delay, s.scheduled_departure_time,
                       s.schedule_relationship::text AS schedule_relationship, s.assigned_stop_id,
                       s.last_seen_at,
                       st.stop_name
                FROM rt_stop_time_updates_hot s
                LEFT JOIN static_stops st
                       ON st.feed_id = s.feed_id
                      AND st.stop_id = COALESCE(s.stop_id, s.assigned_stop_id)
                WHERE s.trip_update_id = ?
                ORDER BY s.stop_sequence ASC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tripUpdateId);
            try (ResultSet rs = statement.executeQuery()) {
                List<StopTimeUpdateDto> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new StopTimeUpdateDto(
                            rs.getLong("trip_update_id"),
                            rs.getInt("stop_sequence"),
                            rs.getString("stop_id"),
                            offsetDateTime(rs, "arrival_time"),
                            nullableInt(rs, "arrival_delay"),
                            offsetDateTime(rs, "scheduled_arrival_time"),
                            offsetDateTime(rs, "departure_time"),
                            nullableInt(rs, "departure_delay"),
                            offsetDateTime(rs, "scheduled_departure_time"),
                            rs.getString("schedule_relationship"),
                            rs.getString("assigned_stop_id"),
                            rs.getString("stop_name"),
                            offsetDateTime(rs, "last_seen_at"),
                            true));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query GTFS data", e);
        }
    }

    /**
     * The trip's static schedule, with scheduled times turned into absolute instants.
     *
     * <p>GTFS times are local to the agency's time zone and may exceed 24:00:00, so the service
     * date, the overflow days and the agency time zone all participate in the conversion.
     */
    private List<StopTimeUpdateDto> scheduledStops(long tripUpdateId) {
        var context = tripContext(tripUpdateId);
        if (context == null) {
            return List.of();
        }

        String sql = """
                SELECT st.stop_sequence, st.stop_id,
                       st.arrival_time, st.arrival_time_overflow_days,
                       st.departure_time, st.departure_time_overflow_days,
                       s.stop_name
                FROM static_stop_times st
                LEFT JOIN static_stops s
                       ON s.feed_id = st.feed_id AND s.stop_id = st.stop_id
                WHERE st.feed_id = CAST(? AS feed_id_enum) AND st.trip_id = ?
                ORDER BY st.stop_sequence ASC
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.feedId());
            statement.setString(2, context.tripId());
            try (ResultSet rs = statement.executeQuery()) {
                List<StopTimeUpdateDto> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new StopTimeUpdateDto(
                            tripUpdateId,
                            rs.getInt("stop_sequence"),
                            rs.getString("stop_id"),
                            null,
                            null,
                            scheduledAt(
                                    context.startDate(),
                                    rs.getObject("arrival_time", LocalTime.class),
                                    nullableShort(rs, "arrival_time_overflow_days"),
                                    context.zone()),
                            null,
                            null,
                            scheduledAt(
                                    context.startDate(),
                                    rs.getObject("departure_time", LocalTime.class),
                                    nullableShort(rs, "departure_time_overflow_days"),
                                    context.zone()),
                            "SCHEDULED",
                            null,
                            rs.getString("stop_name"),
                            null,
                            false));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query GTFS data", e);
        }
    }

    /**
     * Resolves the trip's feed, static trip id, service date and agency time zone. Returns
     * {@code null} when the trip has no matching static trip, in which case only live rows remain.
     */
    private TripContext tripContext(long tripUpdateId) {
        String sql = """
                SELECT h.feed_id::text AS feed_id, m.trip_id, m.start_date,
                       COALESCE(
                           (SELECT a.agency_timezone FROM static_agency a
                             WHERE a.feed_id = h.feed_id
                               AND a.agency_id = COALESCE(r.agency_id, '')),
                           (SELECT a.agency_timezone FROM static_agency a
                             WHERE a.feed_id = h.feed_id LIMIT 1),
                           'UTC') AS time_zone
                FROM rt_trip_updates_hot h
                LEFT JOIN rt_trip_updates_meta m ON m.id = h.trip_update_id
                LEFT JOIN static_routes r
                       ON r.feed_id = h.feed_id AND r.route_id = m.route_id
                WHERE h.trip_update_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tripUpdateId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                var tripId = rs.getString("trip_id");
                if (tripId == null || tripId.isBlank()) {
                    return null;
                }
                return new TripContext(
                        rs.getString("feed_id"),
                        tripId,
                        rs.getObject("start_date", LocalDate.class),
                        zoneOf(rs.getString("time_zone")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query GTFS data", e);
        }
    }

    private static OffsetDateTime scheduledAt(
            LocalDate serviceDate, LocalTime time, Integer overflowDays, ZoneId zone) {
        if (serviceDate == null || time == null) {
            return null;
        }
        var days = overflowDays == null ? 0 : overflowDays;
        return serviceDate.plusDays(days).atTime(time).atZone(zone).toOffsetDateTime();
    }

    private static ZoneId zoneOf(String timeZone) {
        try {
            return timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static Integer nullableShort(ResultSet rs, String column) throws SQLException {
        var value = rs.getShort(column);
        return rs.wasNull() ? null : (int) value;
    }

    private record TripContext(String feedId, String tripId, LocalDate startDate, ZoneId zone) {}

    private <T> PageResponse<T> page(
            String select,
            String count,
            List<String> conditions,
            List<Object> parameters,
            String order,
            int page,
            int size,
            RowMapper<T> mapper) {
        return page(select, count, conditions, parameters, order, page, size, mapper, true);
    }

    private <T> PageResponse<T> page(
            String select,
            String count,
            List<String> conditions,
            List<Object> parameters,
            String order,
            int page,
            int size,
            RowMapper<T> mapper,
            boolean includeTotal) {
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        return page(select + where, count + where, parameters, order, page, size, mapper, includeTotal);
    }

    private <T> PageResponse<T> page(
            String select,
            String count,
            List<Object> parameters,
            String order,
            int page,
            int size,
            RowMapper<T> mapper) {
        return page(select, count, parameters, order, page, size, mapper, true);
    }

    private <T> PageResponse<T> page(
            String select,
            String count,
            List<Object> parameters,
            String order,
            int page,
            int size,
            RowMapper<T> mapper,
            boolean includeTotal) {
        int pageIndex = Math.min(Math.max(page, 0), MAX_PAGE);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        try (Connection connection = dataSource.getConnection()) {
            long total = -1;
            if (includeTotal) {
                try (PreparedStatement statement = connection.prepareStatement(count)) {
                    bind(statement, parameters);
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        total = rs.getLong(1);
                    }
                }
            }

            List<T> items = new ArrayList<>();
            String sql = select + " ORDER BY " + order + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                statement.setInt(parameters.size() + 1, pageSize);
                statement.setLong(parameters.size() + 2, (long) pageIndex * pageSize);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        items.add(mapper.map(rs));
                    }
                }
            }

            int totalPages = includeTotal ? (int) Math.min((total + pageSize - 1) / pageSize, (long) MAX_PAGE + 1) : -1;
            return new PageResponse<>(items, pageIndex, pageSize, total, totalPages);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query GTFS data", e);
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    private static String normalizedSearch(String search) {
        if (search == null) {
            return null;
        }
        var term = search.trim();
        if (term.length() < MIN_SEARCH_LENGTH) {
            return null;
        }
        return term.length() > MAX_SEARCH_LENGTH ? term.substring(0, MAX_SEARCH_LENGTH) : term;
    }

    private static void addLike(List<Object> parameters, String term) {
        var escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        parameters.add("%" + escaped + "%");
    }

    private static String sortColumn(Map<String, String> allowed, String requested, String fallback) {
        return allowed.getOrDefault(requested, fallback);
    }

    private static String direction(String requested) {
        return "desc".equalsIgnoreCase(requested) ? "DESC" : "ASC";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** An aggregated text array as a list; absent stays empty rather than null. */
    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
