package org.example.gtfsynq.shared.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class GtfsStaticArchiveValidatorTest {

    private static final String AGENCY = """
            agency_id,agency_name,agency_url,agency_timezone
            TA,Test Agency,https://example.com,Europe/Berlin
            """;

    private static final String STOPS = """
            stop_id,stop_name,stop_lat,stop_lon
            S1,Central,52.5,13.4
            """;

    private static final String ROUTES = """
            route_id,route_short_name,route_long_name,route_type
            R1,1,Line 1,3
            """;

    private static final String CALENDAR = """
            service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
            serviceA,1,1,1,1,1,0,0,20240101,20241231
            """;

    private static Path archive(String... files) throws Exception {
        var path = Files.createTempFile("gtfs-static-test-", ".zip");
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

    /** A complete feed whose only varying part is {@code trips.txt} and {@code stop_times.txt}. */
    private static String[] feed(String trips, String stopTimes) {
        return new String[] {
            "agency.txt", AGENCY,
            "stops.txt", STOPS,
            "routes.txt", ROUTES,
            "trips.txt", trips,
            "stop_times.txt", stopTimes,
            "calendar.txt", CALENDAR
        };
    }

    private static final String SINGLE_TRIP = """
            route_id,service_id,trip_id
            R1,serviceA,trip1
            """;

    private static final String TWO_TRIPS = """
            route_id,service_id,trip_id
            R1,serviceA,trip1
            R1,serviceA,trip2
            """;

    @Test
    void validArchivePasses() throws Exception {
        var path = archive(feed(SINGLE_TRIP, """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,1
                trip1,08:05:00,08:05:00,S1,2
                """));
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertTrue(report.valid(), () -> report.toString());
        assertEquals(7, report.rowsChecked());
        Files.deleteIfExists(path);
    }

    @Test
    void invalidRowsAndForeignKeysGate() throws Exception {
        var path = archive(
                "agency.txt", """
                agency_id,agency_name,agency_url,agency_timezone
                TA,,not-a-url,Nowhere/Invalid
                """, "stops.txt", """
                stop_id,stop_name,stop_lat,stop_lon
                S1,Central,999,13.4
                S1,Duplicate,52.5,13.4
                """, "routes.txt", """
                route_id,route_short_name,route_type
                R1,,3
                """, "trips.txt", """
                route_id,service_id,trip_id
                MISSING,serviceA,trip1
                """, "stop_times.txt", """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,GHOST,2
                trip1,08:05:00,08:05:00,S1,1
                """);
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertFalse(report.valid());
        // agency name/url/timezone + lat range + dup stop_id + route FK + stop FK + decreasing sequence
        assertTrue(report.violations() >= 7, () -> report.toString());
        assertTrue(report.files().get("stop_times.txt").violations() >= 2);
        Files.deleteIfExists(path);
    }

    @Test
    void missingRequiredFileFails() throws Exception {
        var path = archive("agency.txt", AGENCY);
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertFalse(report.valid());
        assertFalse(report.files().get("stops.txt").present());
        Files.deleteIfExists(path);
    }

    @Test
    void violationCapBoundsMemory() throws Exception {
        var stops = new StringBuilder("stop_id,stop_name,stop_lat,stop_lon\n");
        for (var i = 0; i < 250; i++) {
            stops.append("S").append(i).append(",Bad,999,13.4\n");
        }
        var path = archive(
                "agency.txt",
                AGENCY,
                "stops.txt",
                stops.toString(),
                "routes.txt",
                ROUTES,
                "trips.txt",
                SINGLE_TRIP,
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S0,1
                """,
                "calendar.txt",
                CALENDAR);
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertFalse(report.valid());
        var outcome = report.files().get("stops.txt");
        assertEquals(250, outcome.violations());
        assertEquals(
                StaticValidationReport.MAX_VIOLATIONS_PER_FILE,
                outcome.samples().size());
        assertEquals(250 - StaticValidationReport.MAX_VIOLATIONS_PER_FILE, outcome.droppedViolations());
        Files.deleteIfExists(path);
    }

    @Test
    void quotedFieldsParse() throws Exception {
        // Escaped quotes fight the text-block delimiter, so this fixture stays a plain literal.
        var path = archive(
                "agency.txt",
                """
                agency_id,agency_name,agency_url,agency_timezone
                TA,"Test, Agency",https://example.com,Europe/Berlin
                """,
                "stops.txt",
                "stop_id,stop_name,stop_lat,stop_lon\nS1,\"Central \"\"Hub\"\"\",52.5,13.4\n",
                "routes.txt",
                ROUTES,
                "trips.txt",
                SINGLE_TRIP,
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,1
                """,
                "calendar.txt",
                CALENDAR);
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertTrue(report.valid(), () -> report.toString());
        Files.deleteIfExists(path);
    }

    @Test
    void missingRequiredColumnIsReportedNotThrown() throws Exception {
        // route_type is required: the enumeration predicate must reject the absent cell rather than
        // throw, since YAVI accumulates violations instead of short-circuiting on notBlank.
        var path = archive(
                "agency.txt",
                AGENCY,
                "stops.txt",
                STOPS,
                "routes.txt",
                """
                route_id,route_short_name
                R1,1
                """,
                "trips.txt",
                SINGLE_TRIP,
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,1
                """);
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertFalse(report.valid());
        var routes = report.files().get("routes.txt");
        assertTrue(routes.violations() >= 1, () -> report.toString());
        assertTrue(
                routes.samples().stream().anyMatch(v -> v.field().contains("route_type")),
                () -> routes.samples().toString());
        Files.deleteIfExists(path);
    }

    @Test
    void blankEnumValueIsReportedNotThrown() throws Exception {
        // A present but empty route_type reaches both notBlank and the enumeration predicate.
        var path = archive(
                "agency.txt",
                AGENCY,
                "stops.txt",
                STOPS,
                "routes.txt",
                """
                route_id,route_short_name,route_type
                R1,1,
                """,
                "trips.txt",
                SINGLE_TRIP,
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,1
                """);
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertFalse(report.valid());
        assertTrue(report.files().get("routes.txt").violations() >= 1, () -> report.toString());
        Files.deleteIfExists(path);
    }

    @Test
    void interleavedDecreaseIsDetected() throws Exception {
        // trip1 appears in two non-contiguous runs; a single cursor would miss the decrease.
        var path = archive(feed(TWO_TRIPS, """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,5
                trip2,09:00:00,09:00:00,S1,1
                trip1,08:30:00,08:30:00,S1,3
                """));
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertFalse(report.valid());
        var stopTimes = report.files().get("stop_times.txt");
        assertTrue(
                stopTimes.samples().stream().anyMatch(v -> v.field().equals("stop_sequence") && v.line() == 4),
                () -> report.toString());
        assertTrue(stopTimes.complete());
        Files.deleteIfExists(path);
    }

    @Test
    void interleavedButIncreasingIsValid() throws Exception {
        // Escalating to per-trip tracking must not invent violations for out-of-order grouping.
        var path = archive(feed(TWO_TRIPS, """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,1
                trip2,09:00:00,09:00:00,S1,1
                trip1,08:30:00,08:30:00,S1,2
                trip2,09:30:00,09:30:00,S1,2
                """));
        var report = new GtfsStaticArchiveValidator().validate(path);
        assertTrue(report.valid(), () -> report.toString());
        assertTrue(report.files().get("stop_times.txt").complete());
        Files.deleteIfExists(path);
    }

    @Test
    void trackingCapReportsIncompleteInsteadOfGrowing() throws Exception {
        var path = archive(feed(TWO_TRIPS, """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip1,08:00:00,08:00:00,S1,1
                trip2,09:00:00,09:00:00,S1,1
                trip1,08:30:00,08:30:00,S1,2
                """));
        var report = new GtfsStaticArchiveValidator(1, HashedIdIndex.DEFAULT_MAX_TRACKED_IDS).validate(path);
        assertTrue(report.valid(), () -> report.toString());
        assertFalse(report.files().get("stop_times.txt").complete());
        Files.deleteIfExists(path);
    }
}
