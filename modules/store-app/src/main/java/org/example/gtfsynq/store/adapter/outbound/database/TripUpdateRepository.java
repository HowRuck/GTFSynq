package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;

/**
 * High-throughput persistence for GTFS-RT TripUpdate entities.
 *
 * <p>This class executes pre-shaped batches and does no row-shaping of its own; the "latest wins"
 * collapsing required by the batch rewrite is applied upstream by {@link TripUpdateBatchMapper}.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class TripUpdateRepository {

    private static final int BATCH_SIZE = 5000;

    private static final String UPSERT_TRIP_DESCRIPTORS_SQL = """
            INSERT INTO rt_trip_updates_meta (
                entity_id,
                feed_id,
                id,
                trip_id,
                route_id,
                start_date,
                start_time,
                start_time_overflow_days,
                feed_ts
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                entity_id = EXCLUDED.entity_id,
                feed_id = EXCLUDED.feed_id,
                trip_id = EXCLUDED.trip_id,
                route_id = EXCLUDED.route_id,
                start_date = EXCLUDED.start_date,
                start_time = EXCLUDED.start_time,
                start_time_overflow_days = EXCLUDED.start_time_overflow_days,
                feed_ts = EXCLUDED.feed_ts
            """;

    private static final String APPEND_STOP_TIME_UPDATES_SQL = """
            INSERT INTO rt_stop_time_updates_ht (
                trip_update_id,
                feed_id,
                feed_ts,
                stop_sequence,
                stop_id,
                arrival_time,
                arrival_delay,
                scheduled_arrival_time,
                departure_time,
                departure_delay,
                scheduled_departure_time,
                schedule_relationship,
                assigned_stop_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_update_id, feed_ts, stop_sequence) DO NOTHING
            """;

    private static final String UPSERT_HOT_TRIPS_SQL = """
            INSERT INTO rt_trip_updates_hot (
                trip_update_id,
                feed_id,
                feed_ts
            ) VALUES (?, ?, ?)
            ON CONFLICT (trip_update_id) DO UPDATE SET
                feed_id = EXCLUDED.feed_id,
                feed_ts = EXCLUDED.feed_ts,
                last_seen_at = NOW()
            """;

    private static final String UPSERT_HOT_STOP_TIME_UPDATES_SQL = """
            INSERT INTO rt_stop_time_updates_hot (
                trip_update_id,
                feed_id,
                feed_ts,
                stop_sequence,
                stop_id,
                arrival_time,
                arrival_delay,
                scheduled_arrival_time,
                departure_time,
                departure_delay,
                scheduled_departure_time,
                schedule_relationship,
                assigned_stop_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_update_id, stop_sequence) DO UPDATE SET
                feed_id = EXCLUDED.feed_id,
                feed_ts = EXCLUDED.feed_ts,
                stop_id = EXCLUDED.stop_id,
                arrival_time = EXCLUDED.arrival_time,
                arrival_delay = EXCLUDED.arrival_delay,
                scheduled_arrival_time = EXCLUDED.scheduled_arrival_time,
                departure_time = EXCLUDED.departure_time,
                departure_delay = EXCLUDED.departure_delay,
                scheduled_departure_time = EXCLUDED.scheduled_departure_time,
                schedule_relationship = EXCLUDED.schedule_relationship,
                assigned_stop_id = EXCLUDED.assigned_stop_id,
                last_seen_at = NOW()
            """;

    private final DataSource dataSource;

    /**
     * Persists one shaped batch using a single pooled connection.
     *
     * <p>Sharing one connection across the descriptor, history, and hot-table writes avoids a pool
     * checkout per statement and keeps the whole write in a single database transaction when the
     * caller runs inside one.
     *
     * @param rows the row streams to write, as produced by {@link TripUpdateBatchMapper}
     * @return per-phase DB timings for metrics
     */
    public FlushTimings write(TripUpdateRows rows) {
        if (rows.isEmpty()) {
            return new FlushTimings(0, 0, 0, 0);
        }

        try (Connection connection = dataSource.getConnection()) {
            var start = System.nanoTime();
            batchUpdate(connection, UPSERT_TRIP_DESCRIPTORS_SQL, rows.descriptors(), this::bindTripDescriptor);
            var descriptorsNanos = System.nanoTime() - start;

            start = System.nanoTime();
            batchUpdate(
                    connection,
                    APPEND_STOP_TIME_UPDATES_SQL,
                    rows.historyStopTimes(),
                    this::bindStopTimeUpdateParameters);
            var stopTimesNanos = System.nanoTime() - start;

            start = System.nanoTime();
            batchUpdate(connection, UPSERT_HOT_TRIPS_SQL, rows.hotTripRows(), this::bindHotTripRow);
            var hotTripRowsNanos = System.nanoTime() - start;

            start = System.nanoTime();
            batchUpdate(
                    connection,
                    UPSERT_HOT_STOP_TIME_UPDATES_SQL,
                    rows.hotStopTimes(),
                    this::bindStopTimeUpdateParameters);
            var hotStopTimesNanos = System.nanoTime() - start;

            return new FlushTimings(descriptorsNanos, stopTimesNanos, hotTripRowsNanos, hotStopTimesNanos);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int deleteAllByLastSeenAtBefore(LocalDateTime lastSeenAt) {
        var sql = """
                DELETE FROM rt_trip_updates_hot
                WHERE last_seen_at < ?
                """;

        try (Connection connection = dataSource.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setObject(1, lastSeenAt);
            return preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void bindHotTripRow(PreparedStatement preparedStatement, TripUpdateRows.HotTripRow row)
            throws SQLException {
        preparedStatement.setLong(1, row.tripUpdateId());
        preparedStatement.setObject(2, row.feedId(), Types.OTHER);
        preparedStatement.setTimestamp(3, Timestamp.from(row.feedTs()));
    }

    private void bindTripDescriptor(PreparedStatement preparedStatement, TripDescriptorDto descriptor)
            throws SQLException {
        preparedStatement.setString(1, descriptor.entityId());
        preparedStatement.setObject(2, descriptor.feedId(), Types.OTHER);
        preparedStatement.setLong(3, descriptor.id());
        preparedStatement.setString(4, descriptor.tripId());
        preparedStatement.setString(5, descriptor.routeId());
        preparedStatement.setDate(6, descriptor.startDate() == null ? null : Date.valueOf(descriptor.startDate()));
        preparedStatement.setTime(7, descriptor.startTime() == null ? null : Time.valueOf(descriptor.startTime()));
        preparedStatement.setObject(8, descriptor.startTimeOverflowDays(), Types.SMALLINT);
        preparedStatement.setTimestamp(9, Timestamp.from(descriptor.feedTs()));
    }

    /**
     * Binds TripStopTimeUpdateDto parameters to a prepared statement
     *
     * @param preparedStatement the prepared statement
     * @param update            the trip stop time update DTO
     * @throws SQLException if a database access error occurs
     */
    private void bindStopTimeUpdateParameters(PreparedStatement preparedStatement, TripStopTimeUpdateDto update)
            throws SQLException {

        preparedStatement.setLong(1, update.tripKey());
        preparedStatement.setObject(2, update.feedId(), Types.OTHER);
        preparedStatement.setTimestamp(3, Timestamp.from(update.feedTs()));
        setNullableInteger(preparedStatement, 4, update.stopSequence());
        preparedStatement.setString(5, update.stopId());
        preparedStatement.setTimestamp(6, update.arrivalTime() == null ? null : Timestamp.from(update.arrivalTime()));
        setNullableInteger(preparedStatement, 7, update.arrivalDelay());
        preparedStatement.setTimestamp(
                8, update.scheduledArrivalTime() == null ? null : Timestamp.from(update.scheduledArrivalTime()));
        preparedStatement.setTimestamp(
                9, update.departureTime() == null ? null : Timestamp.from(update.departureTime()));
        setNullableInteger(preparedStatement, 10, update.departureDelay());
        preparedStatement.setTimestamp(
                11, update.scheduledDepartureTime() == null ? null : Timestamp.from(update.scheduledDepartureTime()));
        preparedStatement.setObject(
                12, update.scheduleRelationship() == null ? null : update.scheduleRelationship(), Types.OTHER);
        preparedStatement.setString(13, update.assignedStopId());
    }

    private void setNullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private <T> void batchUpdate(Connection connection, String sql, List<T> items, BatchBinder<T> binder)
            throws SQLException {
        if (items == null || items.isEmpty()) {
            return;
        }

        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            var pending = 0;
            for (var item : items) {
                binder.bind(preparedStatement, item);
                preparedStatement.addBatch();
                if (++pending >= BATCH_SIZE) {
                    preparedStatement.executeBatch();
                    preparedStatement.clearBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                preparedStatement.executeBatch();
            }
        }
    }

    @FunctionalInterface
    private interface BatchBinder<T> {
        void bind(PreparedStatement preparedStatement, T item) throws SQLException;
    }

    /**
     * Per-phase database timings for one {@link #write} call, in nanoseconds.
     */
    public record FlushTimings(
            long descriptorsNanos, long stopTimesNanos, long hotTripRowsNanos, long hotStopTimesNanos) {}
}
