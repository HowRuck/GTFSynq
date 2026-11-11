package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTimes;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StopSelection;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;
import org.example.gtfsynq.store.service.metrics.StaticLookupMetrics;

/**
 * Reads the static stops of a trip: their sequence, stop id, scheduled times and the agency time
 * zone. Used to complete realtime updates that omit an identifier and to derive the effective delay
 * and event times at write time.
 *
 * <p>One query per feed, with the trip ids as an array, keeps the cost independent of the batch's
 * update count. A failed read fails the flush instead of degrading to message order: writing
 * approximate sequences for a trip the static schedule does describe would overwrite rows that a
 * later poll may never rewrite, since an unchanged delay is deduplicated.
 */
@Slf4j
@ApplicationScoped
@RequiredArgsConstructor
public class StaticStopTimeLookup implements StaticStopTimes {

    private static final String STOPS_SQL = """
            SELECT trip_id, stop_id, stop_sequence,
                   arrival_time, arrival_time_overflow_days,
                   departure_time, departure_time_overflow_days
            FROM static_stop_times
            WHERE feed_id = CAST(? AS feed_id_enum)
              AND trip_id = ANY(?)
              AND (stop_id = ANY(?) OR stop_sequence = ANY(?))
            ORDER BY trip_id, stop_sequence
            """;

    /** One row per trip, so the agency lookup is paid per trip and not per stop. */
    private static final String TIMEZONES_SQL = """
            SELECT t.trip_id, a.agency_timezone
            FROM static_trips t
            JOIN static_routes r
              ON r.feed_id = t.feed_id
             AND r.route_id = t.route_id
            JOIN static_agency a
              ON a.feed_id = r.feed_id
             AND a.agency_id = COALESCE(r.agency_id, '')
            WHERE t.feed_id = CAST(? AS feed_id_enum)
              AND t.trip_id = ANY(?)
            """;

    /** Single-agency feeds may omit {@code agency_id}; the first agency then speaks for the feed. */
    private static final String FEED_TIMEZONE_SQL = """
            SELECT agency_timezone
            FROM static_agency
            WHERE feed_id = CAST(? AS feed_id_enum)
            ORDER BY agency_id
            LIMIT 1
            """;

    private final DataSource dataSource;

    private final StaticLookupMetrics metrics;

    @Override
    public Map<TripKey, List<StaticStopTime>> load(Map<TripKey, StopSelection> selections) {
        var stops = new HashMap<TripKey, List<StaticStopTime>>(selections.size() * 2 + 16);
        var started = System.nanoTime();
        try (var connection = dataSource.getConnection()) {
            for (var query : groupByFeed(selections)) {
                if (query.hasStops()) {
                    loadFeed(connection, query, stops);
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException(SqlLogSanitizer.sanitize(e));
        }

        var nanos = System.nanoTime() - started;
        var loaded = stops.values().stream().mapToInt(List::size).sum();
        metrics.record(loaded, nanos);
        log.debug(
                "Loaded {} static stops for {} trips in {} ms",
                loaded,
                selections.size(),
                TimeUnit.NANOSECONDS.toMillis(nanos));
        return stops;
    }

    /** One feed's trips and the union of the stops wanted from them. */
    private record FeedQuery(String feedId, Set<String> tripIds, Set<String> stopIds, Set<Integer> sequences) {

        boolean hasStops() {
            return !stopIds.isEmpty() || !sequences.isEmpty();
        }
    }

    private static Collection<FeedQuery> groupByFeed(Map<TripKey, StopSelection> selections) {
        var byFeed = new HashMap<String, FeedQuery>();
        selections.forEach((key, selection) -> {
            var query = byFeed.computeIfAbsent(
                    key.feedId(), feedId -> new FeedQuery(feedId, new HashSet<>(), new HashSet<>(), new HashSet<>()));
            query.tripIds().add(key.tripId());
            query.stopIds().addAll(selection.stopIds());
            query.sequences().addAll(selection.sequences());
        });
        return byFeed.values();
    }

    private static void loadFeed(Connection connection, FeedQuery query, Map<TripKey, List<StaticStopTime>> stops)
            throws SQLException {
        var timezones = loadTimezones(connection, query);
        try (var statement = connection.prepareStatement(STOPS_SQL)) {
            statement.setString(1, query.feedId());
            statement.setArray(2, textArray(connection, query.tripIds()));
            statement.setArray(3, textArray(connection, query.stopIds()));
            statement.setArray(
                    4, connection.createArrayOf("integer", query.sequences().toArray()));
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var tripId = rs.getString("trip_id");
                    stops.computeIfAbsent(new TripKey(query.feedId(), tripId), key -> new ArrayList<>())
                            .add(new StaticStopTime(
                                    rs.getString("stop_id"),
                                    rs.getInt("stop_sequence"),
                                    rs.getObject("arrival_time", LocalTime.class),
                                    nullableShort(rs, "arrival_time_overflow_days"),
                                    rs.getObject("departure_time", LocalTime.class),
                                    nullableShort(rs, "departure_time_overflow_days"),
                                    timezones.get(tripId)));
                }
            }
        }
    }

    /**
     * The agency time zone of each trip, one row per trip so the agency join is paid per trip and
     * not per stop. A trip without a matching agency gets the feed's first agency, or UTC.
     */
    private static Map<String, String> loadTimezones(Connection connection, FeedQuery query) throws SQLException {
        var timezones = new HashMap<String, String>();
        try (var statement = connection.prepareStatement(TIMEZONES_SQL)) {
            statement.setString(1, query.feedId());
            statement.setArray(2, textArray(connection, query.tripIds()));
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var timezone = rs.getString("agency_timezone");
                    if (timezone != null) {
                        timezones.put(rs.getString("trip_id"), timezone);
                    }
                }
            }
        }

        if (timezones.size() < query.tripIds().size()) {
            var fallback = loadFeedTimezone(connection, query.feedId());
            query.tripIds().forEach(tripId -> timezones.putIfAbsent(tripId, fallback));
        }
        return timezones;
    }

    /** Falls back to UTC, the same default the read path uses, when the feed has no agency. */
    private static String loadFeedTimezone(Connection connection, String feedId) throws SQLException {
        try (var statement = connection.prepareStatement(FEED_TIMEZONE_SQL)) {
            statement.setString(1, feedId);
            try (var rs = statement.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : "UTC";
            }
        }
    }

    private static Array textArray(Connection connection, Collection<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }

    private static Short nullableShort(ResultSet rs, String column) throws SQLException {
        var value = rs.getShort(column);
        return rs.wasNull() ? null : value;
    }
}
