package org.example.gtfsynq.shared.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;
import org.junit.jupiter.api.Test;

class GtfsStaticArchiveReaderTest {

    private static final String AGENCY = """
            agency_id,agency_name,agency_url,agency_timezone
            TA,Test Agency,https://example.com,Europe/Berlin
            """;

    private static final String STOPS = """
            stop_id,stop_name,stop_lat,stop_lon,wheelchair_boarding
            S1,Central,52.5,13.4,
            S2,North,52.6,13.5,1
            """;

    private static final String CALENDAR = """
            service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
            serviceA,1,1,1,1,1,0,0,20240101,20241231
            """;

    private static final String CALENDAR_DATES = """
            service_id,date,exception_type
            serviceA,20240701,2
            """;

    /** Collects everything the reader hands over, keeping the order the callbacks arrived in. */
    private static final class RecordingSink implements GtfsStaticArchiveReader.StaticRowSink {

        private final List<String> calls = new ArrayList<>();

        private final List<StopTimeRow> stopTimes = new ArrayList<>();

        private final List<StopRow> stops = new ArrayList<>();

        @Override
        public void onAgency(List<GtfsStaticRows.AgencyRow> rows) {
            calls.add("agency:" + rows.size());
            rows.forEach(row -> assertEquals("TA", row.agencyId()));
        }

        @Override
        public void onStops(List<StopRow> rows) {
            calls.add("stops:" + rows.size());
            stops.addAll(rows);
        }

        @Override
        public void onRoutes(List<GtfsStaticRows.RouteRow> rows) {
            calls.add("routes:" + rows.size());
        }

        @Override
        public void onTrips(List<GtfsStaticRows.TripRow> rows) {
            calls.add("trips:" + rows.size());
        }

        @Override
        public void onStopTimes(List<StopTimeRow> rows) {
            calls.add("stopTimes:" + rows.size());
            stopTimes.addAll(rows);
        }

        @Override
        public void onCalendar(List<GtfsStaticRows.CalendarRow> rows) {
            calls.add("calendar:" + rows.size());
        }

        @Override
        public void onCalendarDates(List<GtfsStaticRows.CalendarDateRow> rows) {
            calls.add("calendarDates:" + rows.size());
        }
    }

    private static Path archive(String... files) throws Exception {
        var path = Files.createTempFile("gtfs-static-reader-test-", ".zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var i = 0; i < files.length; i += 2) {
                out.putNextEntry(new ZipEntry(files[i]));
                out.write(files[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        path.toFile().deleteOnExit();
        return path;
    }

    private static String[] feed(String stopTimes) {
        return new String[] {
            "agency.txt", AGENCY,
            "stops.txt", STOPS,
            "routes.txt", """
                    route_id,route_short_name,route_type
                    R1,1,3
                    """,
            "trips.txt", """
                    route_id,service_id,trip_id
                    R1,serviceA,trip1
                    """,
            "stop_times.txt", stopTimes,
            "calendar.txt", CALENDAR,
            "calendar_dates.txt", CALENDAR_DATES
        };
    }

    private static final String TWO_STOP_TIMES = """
            trip_id,arrival_time,departure_time,stop_id,stop_sequence
            trip1,08:00:00,08:00:00,S1,1
            trip1,25:10:00,25:10:00,S2,2
            """;

    @Test
    void readsEveryFileInValidatorOrder() throws Exception {
        var sink = new RecordingSink();
        new GtfsStaticArchiveReader().read(archive(feed(TWO_STOP_TIMES)), sink);

        assertEquals(
                List.of("agency:1", "stops:2", "routes:1", "trips:1", "stopTimes:2", "calendar:1", "calendarDates:1"),
                sink.calls);
        assertEquals(2, sink.stops.size());
        assertEquals(2, sink.stopTimes.size());
        assertEquals("25:10:00", sink.stopTimes.get(1).arrivalTime(), "times are handed over as written");
    }

    @Test
    void blankCellsReadAsNull() throws Exception {
        var sink = new RecordingSink();
        new GtfsStaticArchiveReader().read(archive(feed(TWO_STOP_TIMES)), sink);

        assertNull(sink.stops.get(0).wheelchairBoarding());
        assertEquals("1", sink.stops.get(1).wheelchairBoarding());
    }

    @Test
    void skipsFilesTheArchiveDoesNotCarry() throws Exception {
        var sink = new RecordingSink();
        new GtfsStaticArchiveReader().read(archive("agency.txt", AGENCY, "calendar.txt", CALENDAR), sink);

        assertEquals(List.of("agency:1", "calendar:1"), sink.calls);
    }

    @Test
    void batchesByConfiguredSizeAndKeepsTheRemainder() throws Exception {
        var stopTimes = new StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n");
        for (var i = 1; i <= 5; i++) {
            stopTimes
                    .append("trip1,08:0")
                    .append(i)
                    .append(":00,08:0")
                    .append(i)
                    .append(":00,S1,")
                    .append(i)
                    .append('\n');
        }
        var sink = new RecordingSink();
        new GtfsStaticArchiveReader().read(archive(feed(stopTimes.toString())), 2, sink);

        assertEquals(
                List.of(
                        "agency:1",
                        "stops:2",
                        "routes:1",
                        "trips:1",
                        "stopTimes:2",
                        "stopTimes:2",
                        "stopTimes:1",
                        "calendar:1",
                        "calendarDates:1"),
                sink.calls);
        assertEquals(5, sink.stopTimes.size());
    }

    @Test
    void headerOnlyFileYieldsNoCallback() throws Exception {
        var sink = new RecordingSink();
        new GtfsStaticArchiveReader()
                .read(archive(feed("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n")), sink);

        assertEquals(
                List.of("agency:1", "stops:2", "routes:1", "trips:1", "calendar:1", "calendarDates:1"), sink.calls);
    }

    @Test
    void rejectsANonPositiveBatchSize() throws Exception {
        var path = archive(feed(TWO_STOP_TIMES));
        try {
            new GtfsStaticArchiveReader().read(path, 0, new RecordingSink());
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("batch size"));
            return;
        }
        throw new AssertionError("a non-positive batch size should be rejected");
    }
}
