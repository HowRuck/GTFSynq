package org.example.gtfsynq.store.adapter.outbound.database;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * High-throughput persistence for GTFS-RT TripUpdate entities
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class TripUpdateRepository {

    private static final int BATCH_SIZE = 512;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Upserts the parent trip update row
     *
     * @param descriptors the list of trip descriptors to upsert
     */
    public void upsertTripDescriptors(List<TripDescriptorDto> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return;
        }

        var sql = """
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

        jdbcTemplate.batchUpdate(sql, descriptors, BATCH_SIZE, (preparedStatement, descriptor) -> {
            preparedStatement.setString(1, descriptor.entityId());
            preparedStatement.setObject(2, descriptor.feedId(), Types.OTHER);
            preparedStatement.setLong(3, descriptor.id());
            preparedStatement.setString(4, descriptor.tripId());
            preparedStatement.setString(5, descriptor.routeId());
            preparedStatement.setDate(6, descriptor.startDate() == null ? null : Date.valueOf(descriptor.startDate()));
            preparedStatement.setTime(7, descriptor.startTime() == null ? null : Time.valueOf(descriptor.startTime()));
            preparedStatement.setObject(8, descriptor.startTimeOverflowDays(), Types.SMALLINT);
            preparedStatement.setTimestamp(9, Timestamp.from(descriptor.feedTs()));
        });
    }

    /**
     * Appends a list of trip stop time updates to the database.
     *
     * @param updates the list of trip stop time updates to append
     */
    public void appendTripUpdates(List<TripStopTimeUpdateDto> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        var sql = """
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

        jdbcTemplate.batchUpdate(sql, updates, BATCH_SIZE, this::bindStopTimeUpdateParameters);
    }

    public void upsertHotTrips(List<TripDescriptorDto> tripDescriptors, List<TripStopTimeUpdateDto> stopTimeUpdates) {
        var rows = new LinkedList<HotTripUpdateRow>();

        for (var tripDescriptor : tripDescriptors) {
            rows.add(new HotTripUpdateRow(tripDescriptor.id(), tripDescriptor.feedId(), tripDescriptor.feedTs()));
        }
        for (var update : stopTimeUpdates) {
            rows.add(new HotTripUpdateRow(update.tripKey(), update.feedId(), update.feedTs()));
        }

        upsertHotTripRows(rows);

        upsertHotStopTimeUpdates(stopTimeUpdates);
    }

    public int deleteAllByLastSeenAtBefore(LocalDateTime lastSeenAt) {
        var sql = """
				DELETE FROM rt_trip_updates_hot
				WHERE last_seen_at < ?
				""";

        return jdbcTemplate.update(sql, lastSeenAt);
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

    private void upsertHotTripRows(List<HotTripUpdateRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        var uniqueUpdateRows = rows.stream().distinct().toList();

        var sql = """
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

        jdbcTemplate.batchUpdate(sql, uniqueUpdateRows, BATCH_SIZE, (preparedStatement, row) -> {
            preparedStatement.setLong(1, row.tripUpdateId());
            preparedStatement.setObject(2, row.feedId(), Types.OTHER);
            preparedStatement.setTimestamp(3, Timestamp.from(row.feedTs()));
        });
    }

    private void upsertHotStopTimeUpdates(List<TripStopTimeUpdateDto> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        var sql = """
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

        jdbcTemplate.batchUpdate(sql, updates, BATCH_SIZE, this::bindStopTimeUpdateParameters);
    }

    private void setNullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private record HotTripUpdateRow(long tripUpdateId, String feedId, Instant feedTs) {}
}
