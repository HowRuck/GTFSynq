package org.example.gtfsynq.store.adapter.outbound.database;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.shared.util.GtfsFeedFormatter;
import org.example.gtfsynq.shared.validation.GtfsStaticArchiveReader;
import org.example.gtfsynq.shared.validation.GtfsStaticArchiveReader.StaticRowSink;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.AgencyRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.CalendarDateRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.CalendarRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.RouteRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.TripRow;
import org.example.gtfsynq.store.service.metrics.StaticPersistenceMetrics;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

/**
 * Writes one static feed revision, replacing whatever revision that feed held before.
 *
 * <p>Obtained from {@link StaticFeedRepository#openRevision}, which has already cleared the feed's
 * previous rows, so this only has to insert what the archive streams in. It is a
 * {@link StaticRowSink}: {@link GtfsStaticArchiveReader} hands it the rows of each file in turn and
 * they are batched into that file's insert as they arrive, so the whole archive is never held in
 * memory on either side.
 *
 * <p>The write is one transaction covering every table: a feed is either fully at the new revision
 * or fully at the old one, never half-updated. That is deliberately a plain connection-level
 * transaction rather than a JTA one - the load spans a full archive read, and holding a container
 * transaction open for that would pin a pooled connection for as long as the archive takes.
 *
 * <p>Closing without {@link #commit()} rolls back and returns the connection, so a failed read or a
 * bind error cannot leave a feed's rows partly deleted.
 */
@Slf4j
public final class StaticFeedWriter implements StaticRowSink, AutoCloseable {

    /** Table names, as keyed in the upsert statements and the row-count report. */
    static final String AGENCY = "static_agency";

    static final String STOPS = "static_stops";

    static final String ROUTES = "static_routes";

    static final String TRIPS = "static_trips";

    static final String STOP_TIMES = "static_stop_times";

    static final String CALENDAR = "static_calendar";

    static final String CALENDAR_DATES = "static_calendar_dates";

    /**
     * Upsert per table. The static tables carry no foreign keys - the validator has already proven
     * every reference resolves within the same revision - so each table is written independently and
     * only the conflict key is excluded from the update.
     */
    private static final Map<String, String> UPSERT_SQL = upserts();

    /**
     * COPY target for the largest table. Its 23 columns make a 5,000-row bind batch exceed the
     * protocol's parameter limit, and incremental index maintenance dominates a multi-million-row
     * load; COPY avoids both.
     */
    private static final String STOP_TIMES_COPY_SQL = """
            COPY static_stop_times (
                feed_id,
                trip_id,
                stop_sequence,
                stop_id,
                arrival_time,
                arrival_time_overflow_days,
                departure_time,
                departure_time_overflow_days,
                pickup_type,
                drop_off_type,
                shape_dist_traveled,
                timepoint,
                location_group_id,
                location_id,
                stop_headsign,
                start_pickup_drop_off_window,
                start_pickup_drop_off_window_overflow_days,
                end_pickup_drop_off_window,
                end_pickup_drop_off_window_overflow_days,
                continuous_pickup,
                continuous_drop_off,
                pickup_booking_rule_id,
                drop_off_booking_rule_id
            ) FROM STDIN WITH (FORMAT csv)
            """;

    private static Map<String, String> upserts() {
        var sql = new LinkedHashMap<String, String>();

        sql.put(AGENCY, """
                INSERT INTO static_agency (
                    feed_id,
                    agency_id,
                    agency_name,
                    agency_url,
                    agency_timezone,
                    agency_lang,
                    agency_fare_url,
                    agency_email,
                    agency_phone,
                    cemv_support
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feed_id, agency_id) DO UPDATE SET
                    agency_name = EXCLUDED.agency_name,
                    agency_url = EXCLUDED.agency_url,
                    agency_timezone = EXCLUDED.agency_timezone,
                    agency_lang = EXCLUDED.agency_lang,
                    agency_fare_url = EXCLUDED.agency_fare_url,
                    agency_email = EXCLUDED.agency_email,
                    agency_phone = EXCLUDED.agency_phone,
                    cemv_support = EXCLUDED.cemv_support
                """);

        sql.put(STOPS, """
                INSERT INTO static_stops (
                    feed_id,
                    stop_id,
                    stop_name,
                    stop_lat,
                    stop_lon,
                    stop_url,
                    location_type,
                    parent_station,
                    stop_timezone,
                    wheelchair_boarding,
                    stop_code,
                    tts_stop_name,
                    stop_desc,
                    zone_id,
                    level_id,
                    platform_code,
                    stop_access
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feed_id, stop_id) DO UPDATE SET
                    stop_name = EXCLUDED.stop_name,
                    stop_lat = EXCLUDED.stop_lat,
                    stop_lon = EXCLUDED.stop_lon,
                    stop_url = EXCLUDED.stop_url,
                    location_type = EXCLUDED.location_type,
                    parent_station = EXCLUDED.parent_station,
                    stop_timezone = EXCLUDED.stop_timezone,
                    wheelchair_boarding = EXCLUDED.wheelchair_boarding,
                    stop_code = EXCLUDED.stop_code,
                    tts_stop_name = EXCLUDED.tts_stop_name,
                    stop_desc = EXCLUDED.stop_desc,
                    zone_id = EXCLUDED.zone_id,
                    level_id = EXCLUDED.level_id,
                    platform_code = EXCLUDED.platform_code,
                    stop_access = EXCLUDED.stop_access
                """);

        sql.put(ROUTES, """
                INSERT INTO static_routes (
                    feed_id,
                    route_id,
                    route_short_name,
                    route_long_name,
                    route_type,
                    route_url,
                    route_color,
                    route_text_color,
                    route_sort_order,
                    continuous_pickup,
                    continuous_drop_off,
                    agency_id,
                    route_desc,
                    network_id,
                    cemv_support
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feed_id, route_id) DO UPDATE SET
                    route_short_name = EXCLUDED.route_short_name,
                    route_long_name = EXCLUDED.route_long_name,
                    route_type = EXCLUDED.route_type,
                    route_url = EXCLUDED.route_url,
                    route_color = EXCLUDED.route_color,
                    route_text_color = EXCLUDED.route_text_color,
                    route_sort_order = EXCLUDED.route_sort_order,
                    continuous_pickup = EXCLUDED.continuous_pickup,
                    continuous_drop_off = EXCLUDED.continuous_drop_off,
                    agency_id = EXCLUDED.agency_id,
                    route_desc = EXCLUDED.route_desc,
                    network_id = EXCLUDED.network_id,
                    cemv_support = EXCLUDED.cemv_support
                """);

        sql.put(TRIPS, """
                INSERT INTO static_trips (
                    feed_id,
                    trip_id,
                    route_id,
                    service_id,
                    direction_id,
                    wheelchair_accessible,
                    bikes_allowed,
                    trip_headsign,
                    trip_short_name,
                    block_id,
                    shape_id,
                    cars_allowed,
                    safe_duration_factor,
                    safe_duration_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feed_id, trip_id) DO UPDATE SET
                    route_id = EXCLUDED.route_id,
                    service_id = EXCLUDED.service_id,
                    direction_id = EXCLUDED.direction_id,
                    wheelchair_accessible = EXCLUDED.wheelchair_accessible,
                    bikes_allowed = EXCLUDED.bikes_allowed,
                    trip_headsign = EXCLUDED.trip_headsign,
                    trip_short_name = EXCLUDED.trip_short_name,
                    block_id = EXCLUDED.block_id,
                    shape_id = EXCLUDED.shape_id,
                    cars_allowed = EXCLUDED.cars_allowed,
                    safe_duration_factor = EXCLUDED.safe_duration_factor,
                    safe_duration_offset = EXCLUDED.safe_duration_offset
                """);

        sql.put(STOP_TIMES, """
                INSERT INTO static_stop_times (
                    feed_id,
                    trip_id,
                    stop_sequence,
                    stop_id,
                    arrival_time,
                    arrival_time_overflow_days,
                    departure_time,
                    departure_time_overflow_days,
                    pickup_type,
                    drop_off_type,
                    shape_dist_traveled,
                    timepoint,
                    location_group_id,
                    location_id,
                    stop_headsign,
                    start_pickup_drop_off_window,
                    start_pickup_drop_off_window_overflow_days,
                    end_pickup_drop_off_window,
                    end_pickup_drop_off_window_overflow_days,
                    continuous_pickup,
                    continuous_drop_off,
                    pickup_booking_rule_id,
                    drop_off_booking_rule_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feed_id, trip_id, stop_sequence) DO UPDATE SET
                    stop_id = EXCLUDED.stop_id,
                    arrival_time = EXCLUDED.arrival_time,
                    arrival_time_overflow_days = EXCLUDED.arrival_time_overflow_days,
                    departure_time = EXCLUDED.departure_time,
                    departure_time_overflow_days = EXCLUDED.departure_time_overflow_days,
                    pickup_type = EXCLUDED.pickup_type,
                    drop_off_type = EXCLUDED.drop_off_type,
                    shape_dist_traveled = EXCLUDED.shape_dist_traveled,
                    timepoint = EXCLUDED.timepoint,
                    location_group_id = EXCLUDED.location_group_id,
                    location_id = EXCLUDED.location_id,
                    stop_headsign = EXCLUDED.stop_headsign,
                    start_pickup_drop_off_window = EXCLUDED.start_pickup_drop_off_window,
                    start_pickup_drop_off_window_overflow_days =
                        EXCLUDED.start_pickup_drop_off_window_overflow_days,
                    end_pickup_drop_off_window = EXCLUDED.end_pickup_drop_off_window,
                    end_pickup_drop_off_window_overflow_days =
                        EXCLUDED.end_pickup_drop_off_window_overflow_days,
                    continuous_pickup = EXCLUDED.continuous_pickup,
                    continuous_drop_off = EXCLUDED.continuous_drop_off,
                    pickup_booking_rule_id = EXCLUDED.pickup_booking_rule_id,
                    drop_off_booking_rule_id = EXCLUDED.drop_off_booking_rule_id
                """);

        sql.put(CALENDAR, """
                INSERT INTO static_calendar (
                    feed_id,
                    service_id,
                    monday,
                    tuesday,
                    wednesday,
                    thursday,
                    friday,
                    saturday,
                    sunday,
                    start_date,
                    end_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (feed_id, service_id) DO UPDATE SET
                    monday = EXCLUDED.monday,
                    tuesday = EXCLUDED.tuesday,
                    wednesday = EXCLUDED.wednesday,
                    thursday = EXCLUDED.thursday,
                    friday = EXCLUDED.friday,
                    saturday = EXCLUDED.saturday,
                    sunday = EXCLUDED.sunday,
                    start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date
                """);

        sql.put(CALENDAR_DATES, """
                INSERT INTO static_calendar_dates (
                    feed_id,
                    service_id,
                    date,
                    exception_type
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (feed_id, service_id, date) DO UPDATE SET
                    exception_type = EXCLUDED.exception_type
                """);

        return Map.copyOf(sql);
    }

    private final Connection connection;

    private final StaticFeedIngested event;

    private final String feedId;

    /** True when this digest is already the loaded revision, in which case rows are dropped. */
    private final boolean unchanged;

    private final StaticPersistenceMetrics metrics;

    private final Map<String, PreparedStatement> statements = new HashMap<>();

    private final Map<String, Long> rowsByTable = new LinkedHashMap<>();

    private CopyIn stopTimesCopy;

    private boolean committed;

    /** Wall-clock start of the revision, for the commit summary. */
    private final long startedAt = System.nanoTime();

    /** Per-table write time and chunk count, reported when the revision commits. */
    private final Map<String, Long> tableNanos = new LinkedHashMap<>();

    private final Map<String, Integer> tableChunks = new LinkedHashMap<>();

    /** Rows streamed into COPY so far, used for coarse progress logging. */
    private long stopTimeRowsCopied;

    private static final long COPY_PROGRESS_INTERVAL = 500_000;

    StaticFeedWriter(
            Connection connection, StaticFeedIngested event, boolean unchanged, StaticPersistenceMetrics metrics) {
        this.connection = connection;
        this.event = event;
        this.feedId = event.getFeedId();
        this.unchanged = unchanged;
        this.metrics = metrics;
    }

    /** Whether the feed already holds this revision, so the archive is discarded instead of written. */
    public boolean unchanged() {
        return unchanged;
    }

    /**
     * Makes the new revision the loaded one.
     *
     * @throws WriteFailed if the rows or the revision marker cannot be committed
     */
    public void commit() throws WriteFailed {
        var start = System.nanoTime();
        try {
            var copyStart = System.nanoTime();
            log.info("Static feed {}: all rows streamed, finalising COPY", feedId);
            endStopTimesCopy();
            log.info("Static feed {}: COPY finalised in {} ms", feedId, (System.nanoTime() - copyStart) / 1_000_000);

            var markerStart = System.nanoTime();
            if (!unchanged) {
                recordRevision();
            }
            log.debug(
                    "Static feed {}: revision marker written in {} ms",
                    feedId,
                    (System.nanoTime() - markerStart) / 1_000_000);

            var commitStart = System.nanoTime();
            log.info("Static feed {}: committing (WAL flush)…", feedId);
            connection.commit();
            committed = true;
            log.info("Static feed {}: commit returned in {} ms", feedId, (System.nanoTime() - commitStart) / 1_000_000);
            if (!unchanged) {
                log.info(
                        "Committed static revision for feed {}: {} rows in {} ms ({}). Stop times used COPY ({} rows, {} chunks, {} ms)",
                        feedId,
                        rowsByTable.values().stream().mapToLong(Long::longValue).sum(),
                        (System.nanoTime() - startedAt) / 1_000_000,
                        perTableMillis(),
                        rowsByTable.getOrDefault(STOP_TIMES, 0L),
                        tableChunks.getOrDefault(STOP_TIMES, 0),
                        tableNanos.getOrDefault(STOP_TIMES, 0L) / 1_000_000);
            }
        } catch (SQLException e) {
            throw new WriteFailed("could not commit static revision of feed " + feedId, e);
        } finally {
            metrics.recordTransactionPhase(StaticPersistenceMetrics.TransactionPhase.COMMIT, System.nanoTime() - start);
        }
    }

    @Override
    public void close() {
        if (!committed) {
            cancelStopTimesCopy();
        }
        try {
            if (!committed && !connection.getAutoCommit()) {
                var start = System.nanoTime();
                try {
                    connection.rollback();
                } finally {
                    metrics.recordTransactionPhase(
                            StaticPersistenceMetrics.TransactionPhase.ROLLBACK, System.nanoTime() - start);
                }
            }
        } catch (SQLException e) {
            // The connection is being discarded anyway; Agroal resets it on return to the pool.
            closeQuietly();
            return;
        }
        closeQuietly();
    }

    @Override
    public void onAgency(List<AgencyRow> rows) {
        write(AGENCY, rows, this::bindAgency);
    }

    @Override
    public void onStops(List<StopRow> rows) {
        write(STOPS, rows, this::bindStop);
    }

    @Override
    public void onRoutes(List<RouteRow> rows) {
        write(ROUTES, rows, this::bindRoute);
    }

    @Override
    public void onTrips(List<TripRow> rows) {
        write(TRIPS, rows, this::bindTrip);
    }

    @Override
    public void onStopTimes(List<StopTimeRow> rows) {
        write(STOP_TIMES, rows, this::bindStopTime);
    }

    @Override
    public void onCalendar(List<CalendarRow> rows) {
        write(CALENDAR, rows, this::bindCalendar);
    }

    @Override
    public void onCalendarDates(List<CalendarDateRow> rows) {
        write(CALENDAR_DATES, rows, this::bindCalendarDate);
    }

    /** Rows written by this writer, per table; empty for an already loaded revision. */
    public Map<String, Long> rowsByTable() {
        return Map.copyOf(rowsByTable);
    }

    /** Rows written by this writer in total. */
    public long rows() {
        return rowsByTable.values().stream().mapToLong(Long::longValue).sum();
    }

    private void recordRevision() throws SQLException {
        var sql = """
                INSERT INTO static_feeds (
                    feed_id,
                    sha256,
                    bucket,
                    object_key,
                    source_url,
                    size_bytes,
                    route_count,
                    stop_count,
                    trip_count,
                    ingested_at,
                    loaded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (feed_id) DO UPDATE SET
                    sha256 = EXCLUDED.sha256,
                    bucket = EXCLUDED.bucket,
                    object_key = EXCLUDED.object_key,
                    source_url = EXCLUDED.source_url,
                    size_bytes = EXCLUDED.size_bytes,
                    route_count = EXCLUDED.route_count,
                    stop_count = EXCLUDED.stop_count,
                    trip_count = EXCLUDED.trip_count,
                    ingested_at = EXCLUDED.ingested_at,
                    loaded_at = NOW()
                """;

        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setObject(1, feedId, Types.OTHER);
            preparedStatement.setString(2, event.getSha256());
            preparedStatement.setString(3, event.getBucket());
            preparedStatement.setString(4, event.getObjectKey());
            preparedStatement.setString(5, emptyToNull(event.getSourceUrl()));
            preparedStatement.setLong(6, event.getSizeBytes());
            preparedStatement.setLong(7, rowsByTable.getOrDefault(ROUTES, 0L));
            preparedStatement.setLong(8, rowsByTable.getOrDefault(STOPS, 0L));
            preparedStatement.setLong(9, rowsByTable.getOrDefault(TRIPS, 0L));
            preparedStatement.setTimestamp(10, Timestamp.from(ingestedAt()));
            preparedStatement.executeUpdate();
        }
    }

    /** The ingest app stamps the moment it uploaded the archive; a missing one means epoch. */
    private Instant ingestedAt() {
        return event.getIngestedAtEpochSeconds() > 0
                ? Instant.ofEpochSecond(event.getIngestedAtEpochSeconds())
                : Instant.EPOCH;
    }

    private <T> void write(String table, List<T> rows, Binder<T> binder) {
        if (unchanged) {
            // Defence in depth: the caller does not stream an already loaded revision, but a row
            // handed to this writer anyway is dropped rather than rewritten.
            return;
        }
        rowsByTable.merge(table, (long) rows.size(), Long::sum);

        if (STOP_TIMES.equals(table)) {
            copyStopTimes(rows);
            return;
        }

        var start = System.nanoTime();
        var failed = true;
        tableChunks.merge(table, 1, Integer::sum);
        try {
            var statement = statementFor(table);
            for (var row : rows) {
                binder.bind(statement, row);
                statement.addBatch();
            }
            statement.executeBatch();
            statement.clearBatch();
            failed = false;
        } catch (SQLException e) {
            throw new WriteFailed("could not write " + rows.size() + " rows to " + table, SqlLogSanitizer.sanitize(e));
        } finally {
            var elapsed = System.nanoTime() - start;
            tableNanos.merge(table, elapsed, Long::sum);
            metrics.recordBatch(table, rows.size(), elapsed, failed);
        }
    }

    /**
     * Streams the chunk into the table's COPY target, keeping the whole revision in one COPY and one
     * transaction.
     */
    private void copyStopTimes(List<?> rows) {
        var start = System.nanoTime();
        var failed = true;
        tableChunks.merge(STOP_TIMES, 1, Integer::sum);
        try {
            if (stopTimesCopy == null) {
                stopTimesCopy = copyManager().copyIn(STOP_TIMES_COPY_SQL);
            }
            var buffer = new StringBuilder(rows.size() * 160);
            for (var row : rows) {
                StopTimeCopyEncoder.appendRow(buffer, feedId, (StopTimeRow) row);
            }
            var bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
            stopTimesCopy.writeToCopy(bytes, 0, bytes.length);
            log.debug(
                    "Copied {} static_stop_times rows ({} bytes) for feed {}, {} chunks so far",
                    rows.size(),
                    bytes.length,
                    feedId,
                    tableChunks.get(STOP_TIMES));
            var before = stopTimeRowsCopied;
            stopTimeRowsCopied += rows.size();
            if (before / COPY_PROGRESS_INTERVAL != stopTimeRowsCopied / COPY_PROGRESS_INTERVAL) {
                log.info(
                        "Static feed {}: copied {} stop-time rows so far ({} chunks)",
                        feedId,
                        stopTimeRowsCopied,
                        tableChunks.get(STOP_TIMES));
            }
            failed = false;
        } catch (SQLException e) {
            throw new WriteFailed(
                    "could not copy " + rows.size() + " rows to " + STOP_TIMES, SqlLogSanitizer.sanitize(e));
        } catch (RuntimeException e) {
            throw new WriteFailed("could not encode " + rows.size() + " rows for " + STOP_TIMES, e);
        } finally {
            var elapsed = System.nanoTime() - start;
            tableNanos.merge(STOP_TIMES, elapsed, Long::sum);
            metrics.recordBatch(STOP_TIMES, rows.size(), elapsed, failed);
        }
    }

    private String perTableMillis() {
        var summary = new StringBuilder();
        tableNanos.forEach((table, nanos) -> {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(table).append('=').append(nanos / 1_000_000).append("ms");
        });
        return summary.toString();
    }

    private CopyManager copyManager() throws SQLException {
        return connection.unwrap(PGConnection.class).getCopyAPI();
    }

    private void endStopTimesCopy() throws SQLException {
        if (stopTimesCopy != null) {
            stopTimesCopy.endCopy();
            stopTimesCopy = null;
        }
    }

    private void cancelStopTimesCopy() {
        if (stopTimesCopy == null) {
            return;
        }
        try {
            stopTimesCopy.cancelCopy();
        } catch (SQLException e) {
            log.debug("Could not cancel COPY for static stop times", e);
        } finally {
            stopTimesCopy = null;
        }
    }

    private PreparedStatement statementFor(String table) throws SQLException {
        var statement = statements.get(table);
        if (statement == null) {
            statement = connection.prepareStatement(UPSERT_SQL.get(table));
            statements.put(table, statement);
        }
        return statement;
    }

    private void bindAgency(PreparedStatement ps, AgencyRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        // A single-agency feed may omit agency_id; the empty string is that agency's key.
        ps.setString(2, row.agencyId() == null ? "" : row.agencyId());
        ps.setString(3, row.agencyName());
        ps.setString(4, row.agencyUrl());
        ps.setString(5, row.agencyTimezone());
        ps.setString(6, row.agencyLang());
        ps.setString(7, row.agencyFareUrl());
        ps.setString(8, row.agencyEmail());
        ps.setString(9, row.agencyPhone());
        setNullableShort(ps, 10, row.cemvSupport(), "cemv_support");
    }

    private void bindStop(PreparedStatement ps, StopRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        ps.setString(2, row.stopId());
        ps.setString(3, row.stopName());
        setNullableDouble(ps, 4, row.stopLat(), "stop_lat");
        setNullableDouble(ps, 5, row.stopLon(), "stop_lon");
        ps.setString(6, row.stopUrl());
        setNullableShort(ps, 7, row.locationType(), "location_type");
        ps.setString(8, row.parentStation());
        ps.setString(9, row.stopTimezone());
        setNullableShort(ps, 10, row.wheelchairBoarding(), "wheelchair_boarding");
        ps.setString(11, row.stopCode());
        ps.setString(12, row.ttsStopName());
        ps.setString(13, row.stopDesc());
        ps.setString(14, row.zoneId());
        ps.setString(15, row.levelId());
        ps.setString(16, row.platformCode());
        setNullableShort(ps, 17, row.stopAccess(), "stop_access");
    }

    private void bindRoute(PreparedStatement ps, RouteRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        ps.setString(2, row.routeId());
        ps.setString(3, row.routeShortName());
        ps.setString(4, row.routeLongName());
        setRequiredShort(ps, 5, row.routeType(), "route_type");
        ps.setString(6, row.routeUrl());
        ps.setString(7, row.routeColor());
        ps.setString(8, row.routeTextColor());
        setNullableInteger(ps, 9, row.routeSortOrder(), "route_sort_order");
        setNullableShort(ps, 10, row.continuousPickup(), "continuous_pickup");
        setNullableShort(ps, 11, row.continuousDropOff(), "continuous_drop_off");
        ps.setString(12, row.agencyId());
        ps.setString(13, row.routeDesc());
        ps.setString(14, row.networkId());
        setNullableShort(ps, 15, row.cemvSupport(), "cemv_support");
    }

    private void bindTrip(PreparedStatement ps, TripRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        ps.setString(2, row.tripId());
        ps.setString(3, row.routeId());
        ps.setString(4, row.serviceId());
        setNullableShort(ps, 5, row.directionId(), "direction_id");
        setNullableShort(ps, 6, row.wheelchairAccessible(), "wheelchair_accessible");
        setNullableShort(ps, 7, row.bikesAllowed(), "bikes_allowed");
        ps.setString(8, row.tripHeadsign());
        ps.setString(9, row.tripShortName());
        ps.setString(10, row.blockId());
        ps.setString(11, row.shapeId());
        setNullableShort(ps, 12, row.carsAllowed(), "cars_allowed");
        setNullableDouble(ps, 13, row.safeDurationFactor(), "safe_duration_factor");
        setNullableDouble(ps, 14, row.safeDurationOffset(), "safe_duration_offset");
    }

    private void bindStopTime(PreparedStatement ps, StopTimeRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        ps.setString(2, row.tripId());
        setRequiredInteger(ps, 3, row.stopSequence(), "stop_sequence");
        ps.setString(4, row.stopId());
        setTime(ps, 5, 6, row.arrivalTime(), "arrival_time");
        setTime(ps, 7, 8, row.departureTime(), "departure_time");
        setNullableShort(ps, 9, row.pickupType(), "pickup_type");
        setNullableShort(ps, 10, row.dropOffType(), "drop_off_type");
        setNullableDouble(ps, 11, row.shapeDistTraveled(), "shape_dist_traveled");
        setNullableShort(ps, 12, row.timepoint(), "timepoint");
        ps.setString(13, row.locationGroupId());
        ps.setString(14, row.locationId());
        ps.setString(15, row.stopHeadsign());
        setTime(ps, 16, 17, row.startPickupDropOffWindow(), "start_pickup_drop_off_window");
        setTime(ps, 18, 19, row.endPickupDropOffWindow(), "end_pickup_drop_off_window");
        setNullableShort(ps, 20, row.continuousPickup(), "continuous_pickup");
        setNullableShort(ps, 21, row.continuousDropOff(), "continuous_drop_off");
        ps.setString(22, row.pickupBookingRuleId());
        ps.setString(23, row.dropOffBookingRuleId());
    }

    private void bindCalendar(PreparedStatement ps, CalendarRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        ps.setString(2, row.serviceId());
        setNullableShort(ps, 3, row.monday(), "monday");
        setNullableShort(ps, 4, row.tuesday(), "tuesday");
        setNullableShort(ps, 5, row.wednesday(), "wednesday");
        setNullableShort(ps, 6, row.thursday(), "thursday");
        setNullableShort(ps, 7, row.friday(), "friday");
        setNullableShort(ps, 8, row.saturday(), "saturday");
        setNullableShort(ps, 9, row.sunday(), "sunday");
        setRequiredDate(ps, 10, row.startDate(), "start_date");
        setRequiredDate(ps, 11, row.endDate(), "end_date");
    }

    private void bindCalendarDate(PreparedStatement ps, CalendarDateRow row) throws SQLException {
        ps.setObject(1, feedId, Types.OTHER);
        ps.setString(2, row.serviceId());
        setRequiredDate(ps, 3, row.date(), "date");
        setRequiredShort(ps, 4, row.exceptionType(), "exception_type");
    }

    /**
     * Binds a GTFS time and the days it overflows into.
     *
     * <p>A GTFS time past 24:00:00 denotes the next day, which a {@code TIME} column cannot hold, so
     * the day count is stored beside it - the same split the realtime tables use for a trip
     * descriptor's start time.
     */
    private void setTime(PreparedStatement ps, int timeIndex, int overflowIndex, String value, String field)
            throws SQLException {
        var parsed = value == null ? null : GtfsFeedFormatter.parseTime(value);
        if (parsed == null) {
            ps.setNull(timeIndex, Types.TIME);
            ps.setNull(overflowIndex, Types.SMALLINT);
            return;
        }
        var time = parsed.value0();
        var overflowDays = parsed.value1();
        ps.setTime(timeIndex, Time.valueOf(time));
        ps.setObject(overflowIndex, overflowDays, Types.SMALLINT);
    }

    private void setRequiredShort(PreparedStatement ps, int index, String value, String field) throws SQLException {
        ps.setObject(index, shortValue(value, field), Types.SMALLINT);
    }

    private void setNullableShort(PreparedStatement ps, int index, String value, String field) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.SMALLINT);
            return;
        }
        ps.setObject(index, shortValue(value, field), Types.SMALLINT);
    }

    private void setRequiredInteger(PreparedStatement ps, int index, String value, String field) throws SQLException {
        ps.setInt(index, intValue(value, field));
    }

    private void setNullableInteger(PreparedStatement ps, int index, String value, String field) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
            return;
        }
        ps.setInt(index, intValue(value, field));
    }

    private void setNullableDouble(PreparedStatement ps, int index, String value, String field) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
            return;
        }
        try {
            ps.setDouble(index, Double.parseDouble(value));
        } catch (NumberFormatException e) {
            throw malformed(field, value, e);
        }
    }

    private void setRequiredDate(PreparedStatement ps, int index, String value, String field) throws SQLException {
        if (value == null) {
            throw new WriteFailed("missing required " + field + " in validated feed " + feedId);
        }
        LocalDate date;
        try {
            date = GtfsFeedFormatter.parseDate(value);
        } catch (RuntimeException e) {
            throw malformed(field, value, e);
        }
        ps.setDate(index, Date.valueOf(date));
    }

    private Short shortValue(String value, String field) {
        if (value == null) {
            throw new WriteFailed("missing required " + field + " in validated feed " + feedId);
        }
        try {
            return Short.parseShort(value);
        } catch (NumberFormatException e) {
            throw malformed(field, value, e);
        }
    }

    private Integer intValue(String value, String field) {
        if (value == null) {
            throw new WriteFailed("missing required " + field + " in validated feed " + feedId);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw malformed(field, value, e);
        }
    }

    /**
     * Describes a cell that did not parse the way the validator said it would.
     *
     * <p>Rows only reach here for an archive that validated, so this means the two passes disagree
     * about the same bytes. Failing the load loudly keeps the previous revision in place; writing a
     * placeholder would not.
     */
    private WriteFailed malformed(String field, String value, RuntimeException cause) {
        return new WriteFailed("invalid " + field + " \"" + value + "\" in validated feed " + feedId, cause);
    }

    private void closeQuietly() {
        statements.values().forEach(statement -> {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // Closing follows a commit or a rollback; a failure here has nothing left to affect.
            }
        });
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Same: the connection is on its way back to the pool.
        }
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement preparedStatement, T row) throws SQLException;
    }

    /** A write failure the caller can act on; the transaction has been rolled back by {@link #close()}. */
    public static final class WriteFailed extends RuntimeException {

        WriteFailed(String message) {
            super(message);
        }

        WriteFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
