package org.example.gtfsynq.api.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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

    public PageResponse<RouteDto> routes(
            String feedId, String search, int page, int size, String sort, String direction) {
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
            String feedId, String search, int page, int size, String sort, String direction) {
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
            String feedId, String search, int page, int size, String sort, String direction) {
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
            String feedId, int page, int size, String sort, String direction, boolean includeTotal) {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (hasText(feedId)) {
            conditions.add("h.feed_id = CAST(? AS feed_id_enum)");
            parameters.add(feedId);
        }

        String select = """
                SELECT h.trip_update_id, h.feed_id::text AS feed_id, h.feed_ts, h.last_seen_at,
                       m.entity_id, m.trip_id, m.route_id, m.start_date, m.start_time,
                       t.trip_short_name, r.route_short_name, r.route_long_name,
                       (SELECT count(*) FROM rt_stop_time_updates_hot s
                        WHERE s.trip_update_id = h.trip_update_id) AS stop_time_update_count,
                       (SELECT COALESCE(s.arrival_delay, s.departure_delay)
                        FROM rt_stop_time_updates_hot s
                        WHERE s.trip_update_id = h.trip_update_id
                          AND COALESCE(s.arrival_delay, s.departure_delay) IS NOT NULL
                        ORDER BY s.stop_sequence ASC
                        LIMIT 1) AS current_delay
                FROM rt_trip_updates_hot h
                LEFT JOIN rt_trip_updates_meta m ON m.id = h.trip_update_id
                LEFT JOIN static_trips t ON t.feed_id = h.feed_id AND t.trip_id = m.trip_id
                LEFT JOIN static_routes r
                       ON r.feed_id = h.feed_id AND r.route_id = COALESCE(t.route_id, m.route_id)
                """;
        String count = "SELECT count(*) FROM rt_trip_updates_hot h";
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
                        rs.getString("route_long_name")),
                includeTotal);
    }

    /** Current stop time updates of one live trip, ordered by stop sequence. */
    public List<StopTimeUpdateDto> liveStopTimeUpdates(long tripUpdateId) {
        String sql = """
                SELECT s.trip_update_id, s.stop_sequence, s.stop_id,
                       s.arrival_time, s.arrival_delay, s.scheduled_arrival_time,
                       s.departure_time, s.departure_delay, s.scheduled_departure_time,
                       s.schedule_relationship::text AS schedule_relationship, s.assigned_stop_id,
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
                            rs.getString("stop_name")));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query GTFS data", e);
        }
    }

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
