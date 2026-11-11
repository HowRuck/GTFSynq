package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StopSelection;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRows.HotTripRow;
import org.example.gtfsynq.store.service.metrics.StaticLookupMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Runs the static lookup and the realtime write against a real PostgreSQL.
 *
 * <p>Opt-in: set {@code GTFSYNQ_TEST_JDBC_URL} (plus {@code GTFSYNQ_TEST_JDBC_USER} and
 * {@code GTFSYNQ_TEST_JDBC_PASSWORD}) to a scratch database that already has the Flyway migrations
 * applied, for example a throwaway TimescaleDB container. The test inserts rows under ids that start
 * with {@code pgtest-} and removes them afterwards, but it must not be pointed at a database whose
 * data matters.
 */
@EnabledIfEnvironmentVariable(named = "GTFSYNQ_TEST_JDBC_URL", matches = ".+")
class PostgresQueriesTest {

    private static final String FEED = "entur";

    private static final Instant FEED_TS = Instant.parse("2026-11-01T12:00:00Z");

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        var source = new PGSimpleDataSource();
        source.setUrl(System.getenv("GTFSYNQ_TEST_JDBC_URL"));
        source.setUser(System.getenv().getOrDefault("GTFSYNQ_TEST_JDBC_USER", "postgres"));
        source.setPassword(System.getenv().getOrDefault("GTFSYNQ_TEST_JDBC_PASSWORD", ""));
        dataSource = source;
        cleanUp();
        execute("""
                INSERT INTO static_agency (feed_id, agency_id, agency_name, agency_url, agency_timezone)
                VALUES ('entur', 'pgtest-agency', 'Test', 'http://x', 'Europe/Warsaw')""", """
                INSERT INTO static_routes (feed_id, route_id, route_type, agency_id)
                VALUES ('entur', 'pgtest-route', 2, 'pgtest-agency'),
                       ('entur', 'pgtest-route-no-agency', 2, NULL)""", """
                INSERT INTO static_trips (feed_id, trip_id, route_id, service_id)
                VALUES ('entur', 'pgtest-t1', 'pgtest-route', 's'),
                       ('entur', 'pgtest-t2', 'pgtest-route-no-agency', 's')""", """
                INSERT INTO static_stop_times
                    (feed_id, trip_id, stop_sequence, stop_id,
                     arrival_time, arrival_time_overflow_days, departure_time, departure_time_overflow_days)
                VALUES ('entur', 'pgtest-t1', 1, 'pgtest-a', '10:00', 0, '10:01', 0),
                       ('entur', 'pgtest-t1', 2, 'pgtest-b', '10:30', 0, '10:31', 0),
                       ('entur', 'pgtest-t1', 3, 'pgtest-c', '01:00', 1, '01:00', 1),
                       ('entur', 'pgtest-t2', 1, 'pgtest-a', '09:00', 0, '09:00', 0)""");
    }

    @AfterEach
    void cleanUp() throws SQLException {
        execute(
                "DELETE FROM rt_trip_updates_hot WHERE trip_update_id IN"
                        + " (SELECT id FROM rt_trip_updates_meta WHERE entity_id LIKE 'pgtest-%')",
                "DELETE FROM rt_stop_time_updates_ht WHERE trip_update_id IN"
                        + " (SELECT id FROM rt_trip_updates_meta WHERE entity_id LIKE 'pgtest-%')",
                "DELETE FROM rt_trip_updates_meta WHERE entity_id LIKE 'pgtest-%'",
                "DELETE FROM static_stop_times WHERE trip_id LIKE 'pgtest-%'",
                "DELETE FROM static_trips WHERE trip_id LIKE 'pgtest-%'",
                "DELETE FROM static_routes WHERE route_id LIKE 'pgtest-%'",
                "DELETE FROM static_agency WHERE agency_id LIKE 'pgtest-%'");
    }

    @Test
    void loadsOnlyTheSelectedStopsWithTheirScheduleAndZone() {
        var lookup = new StaticStopTimeLookup(
                dataSource, new StaticLookupMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var t1 = new TripKey(FEED, "pgtest-t1");

        var loaded = lookup.load(Map.of(t1, new StopSelection(Set.of("pgtest-b"), Set.of(3))));

        var stops = loaded.get(t1);
        assertEquals(List.of(2, 3), stops.stream().map(s -> s.sequence()).toList(), "unselected stop 1 stays out");
        assertEquals("pgtest-b", stops.getFirst().stopId());
        assertEquals(java.time.LocalTime.of(10, 30), stops.getFirst().arrivalTime());
        assertEquals((short) 1, stops.get(1).arrivalOverflowDays(), "a GTFS time past midnight keeps its overflow");
        assertEquals("Europe/Warsaw", stops.getFirst().timezone());
    }

    @Test
    void givesATripWithoutAMatchingAgencyTheFeedsFirstAgencyZone() {
        var lookup = new StaticStopTimeLookup(
                dataSource, new StaticLookupMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var t2 = new TripKey(FEED, "pgtest-t2");

        var loaded = lookup.load(Map.of(t2, new StopSelection(Set.of("pgtest-a"), Set.of())));

        assertFalse(loaded.get(t2).isEmpty());
        assertFalse(loaded.get(t2).getFirst().timezone() == null, "falls back to a feed zone, never null");
    }

    @Test
    void readsNothingForAnEmptySelection() {
        var lookup = new StaticStopTimeLookup(
                dataSource, new StaticLookupMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        var loaded = lookup.load(Map.of(new TripKey(FEED, "pgtest-t1"), new StopSelection(Set.of(), Set.of())));

        assertEquals(Map.of(), loaded);
    }

    @Test
    void writesAndRewritesTheEffectiveColumnsToTheHistoryAndHotTables() throws SQLException {
        var repository = new TripUpdateRepository(dataSource);
        var descriptor = TripDescriptorDto.fromEntity(
                TripDescriptor.newBuilder()
                        .setTripId("pgtest-t1")
                        .setStartDate("20261101")
                        .build(),
                FEED,
                "pgtest-entity",
                FEED_TS);
        var row = TripStopTimeUpdateDto.fromProto(
                        descriptor.id(),
                        FEED,
                        FEED_TS,
                        StopTimeUpdate.newBuilder()
                                .setStopSequence(1)
                                .setStopId("pgtest-a")
                                .setArrival(StopTimeEvent.newBuilder().setTime(FEED_TS.getEpochSecond()))
                                .build())
                .withEffectiveValues(120, FEED_TS, FEED_TS.plusSeconds(60), 120, 200);
        var rows = new TripUpdateRows(
                List.of(descriptor),
                List.of(row),
                List.of(new HotTripRow(descriptor.id(), FEED, FEED_TS)),
                List.of(row));

        repository.write(rows);
        // The same trip again with another delay exercises the hot table's ON CONFLICT DO UPDATE.
        repository.write(new TripUpdateRows(
                List.of(descriptor),
                List.of(),
                List.of(new HotTripRow(descriptor.id(), FEED, FEED_TS)),
                List.of(row.withEffectiveValues(300, FEED_TS, FEED_TS.plusSeconds(60), 300, 310))));

        assertEquals(
                "120|200",
                single("SELECT delay_seconds || '|' || effective_departure_delay FROM rt_stop_time_updates_ht"
                        + " WHERE trip_update_id = " + descriptor.id()),
                "history keeps the first write");
        assertEquals(
                "300|310",
                single(
                        "SELECT effective_arrival_delay || '|' || effective_departure_delay FROM rt_stop_time_updates_hot"
                                + " WHERE trip_update_id = " + descriptor.id()),
                "hot is overwritten by the second write");
        assertNull(
                single("SELECT arrival_delay::text FROM rt_stop_time_updates_hot WHERE trip_update_id = "
                        + descriptor.id()),
                "published columns stay as sent");
    }

    private String single(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            assertEquals(true, rs.next(), "no row for: " + sql);
            return rs.getString(1);
        }
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            for (var sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
