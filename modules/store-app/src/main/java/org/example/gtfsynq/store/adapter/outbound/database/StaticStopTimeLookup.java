package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTimes;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;

/**
 * Reads the stop sequence of each static stop of a trip, used to complete realtime updates that
 * only publish {@code stop_id}.
 *
 * <p>One query per feed, with the trip ids as an array, keeps the cost independent of the batch's
 * update count. A failed read fails the flush instead of degrading to message order: writing
 * approximate sequences for a trip the static schedule does describe would overwrite rows that a
 * later poll may never rewrite, since an unchanged delay is deduplicated.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class StaticStopTimeLookup implements StaticStopTimes {

    private static final String STOPS_SQL = """
            SELECT trip_id, stop_id, stop_sequence
            FROM static_stop_times
            WHERE feed_id = CAST(? AS feed_id_enum)
              AND trip_id = ANY(?)
            ORDER BY trip_id, stop_sequence
            """;

    private final DataSource dataSource;

    @Override
    public Map<TripKey, List<StaticStopTime>> load(Collection<TripKey> keys) {
        var tripIdsByFeed = new HashMap<String, List<String>>();
        for (var key : keys) {
            tripIdsByFeed
                    .computeIfAbsent(key.feedId(), feed -> new ArrayList<>())
                    .add(key.tripId());
        }

        var stops = new HashMap<TripKey, List<StaticStopTime>>(keys.size() * 2 + 16);
        try (var connection = dataSource.getConnection()) {
            for (var entry : tripIdsByFeed.entrySet()) {
                loadFeed(connection, entry.getKey(), entry.getValue(), stops);
            }
        } catch (SQLException e) {
            throw new RuntimeException(SqlLogSanitizer.sanitize(e));
        }
        return stops;
    }

    private static void loadFeed(
            Connection connection, String feedId, List<String> tripIds, Map<TripKey, List<StaticStopTime>> stops)
            throws SQLException {
        try (var statement = connection.prepareStatement(STOPS_SQL)) {
            statement.setString(1, feedId);
            statement.setArray(2, connection.createArrayOf("text", tripIds.toArray()));
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    stops.computeIfAbsent(new TripKey(feedId, rs.getString("trip_id")), key -> new ArrayList<>())
                            .add(new StaticStopTime(rs.getString("stop_id"), rs.getInt("stop_sequence")));
                }
            }
        }
    }
}
