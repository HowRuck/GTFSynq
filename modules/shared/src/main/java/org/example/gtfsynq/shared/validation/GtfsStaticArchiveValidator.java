package org.example.gtfsynq.shared.validation;

import static org.example.gtfsynq.shared.validation.GtfsStaticFile.AGENCY;
import static org.example.gtfsynq.shared.validation.GtfsStaticFile.ROUTES;
import static org.example.gtfsynq.shared.validation.GtfsStaticFile.STOPS;
import static org.example.gtfsynq.shared.validation.GtfsStaticFile.STOP_TIMES;
import static org.example.gtfsynq.shared.validation.GtfsStaticFile.TRIPS;

import am.ik.yavi.core.ConstraintViolation;
import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.FieldMismatchStrategy;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.AgencyRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.RouteRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.TripRow;
import org.example.gtfsynq.shared.validation.StaticValidationReport.FileAccumulator;

/**
 * Streaming validator for GTFS static archives.
 *
 * <p>Opens the staged ZIP with {@link ZipFile} (random entry access, never the whole archive in
 * memory) and parses one entry at a time with FastCSV ({@code de.siegmar:fastcsv}, RFC-4180, zero
 * runtime dependencies), so memory stays flat regardless of {@code stop_times.txt} size. Each
 * record is mapped through a {@link GtfsStaticColumns} index and checked by the row validators in
 * {@link GtfsStaticValidators}; referential checks, duplicate detection and stop-sequence
 * monotonicity run inline against id indexes gathered in the same pass, so each entry is read once.
 *
 * <p>Files are validated in {@link GtfsStaticFile} declaration order, which puts the dimension files
 * before {@code stop_times} to match foreign-key direction. Stateless and thread-safe, so callers can
 * run one instance per feed; the id indexes are the only state and live for a single call.
 */
public final class GtfsStaticArchiveValidator {

    private final int maxTrackedTrips;

    private final int maxTrackedIds;

    /** Uses the default caps for per-trip and per-id tracking. */
    public GtfsStaticArchiveValidator() {
        this(StopSequenceCheck.DEFAULT_MAX_TRACKED_TRIPS, HashedIdIndex.DEFAULT_MAX_TRACKED_IDS);
    }

    /**
     * @param maxTrackedTrips upper bound on distinct trips tracked when a file turns out not to be
     *     grouped; beyond it stop-sequence checking continues for tracked trips only and the file is
     *     reported as not fully checked
     * @param maxTrackedIds upper bound on ids held per index (stops, routes, trips, services) before
     *     referential and duplicate checks stop being reliable and the affected files are reported
     *     as not fully checked
     */
    public GtfsStaticArchiveValidator(int maxTrackedTrips, int maxTrackedIds) {
        this.maxTrackedTrips = maxTrackedTrips;
        this.maxTrackedIds = maxTrackedIds;
    }

    /**
     * Validates one archive.
     *
     * @param archive staged GTFS zip on local disk
     * @return bounded per-file outcome; violation details are capped, counts are not
     * @throws IOException if the archive cannot be opened or read
     */
    public StaticValidationReport validate(Path archive) throws IOException {
        var outcomes = new EnumMap<GtfsStaticFile, FileAccumulator>(GtfsStaticFile.class);
        for (var file : GtfsStaticFile.values()) {
            outcomes.put(file, new FileAccumulator());
        }

        var ids = new ReferencedIds(maxTrackedIds);
        var rowsChecked = 0L;

        try (var zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            for (var file : GtfsStaticFile.values()) {
                rowsChecked += switch (file.kind()) {
                    case DIMENSION -> validateDimension(zip, file, outcomes.get(file), ids);
                    case STOP_TIMES -> validateStopTimes(zip, file, outcomes.get(file), ids);
                    case SERVICE -> readServiceIds(zip, file, outcomes.get(file), ids);
                };
            }
            validateServiceCoverage(outcomes, ids);
        }

        if (ids.anyTruncated()) {
            // An id that was not tracked would read as "absent" and could fabricate a referential
            // violation, so files whose checks depend on the indexes are reported as not fully
            // checked rather than validated against a partial index.
            outcomes.forEach((file, outcome) -> {
                if (file.idDependent()) {
                    outcome.complete = false;
                }
            });
        }

        return StaticValidationReport.of(toReportKeyed(outcomes), rowsChecked);
    }

    /** Reports are keyed by file name, which is what callers and logs speak in. */
    private static Map<String, FileAccumulator> toReportKeyed(EnumMap<GtfsStaticFile, FileAccumulator> outcomes) {
        var keyed = new TreeMap<String, FileAccumulator>();
        outcomes.forEach((file, outcome) -> keyed.put(file.fileName(), outcome));
        return keyed;
    }

    private long validateDimension(ZipFile zip, GtfsStaticFile file, FileAccumulator outcome, ReferencedIds ids)
            throws IOException {
        var entry = findEntry(zip, file);
        if (entry == null) {
            outcome.add(file, 0, "required file \"" + file.fileName() + "\" is missing from the archive");
            return 0;
        }
        outcome.present = true;

        long rows = 0;
        try (var records = open(zip, entry);
                var iterator = records.iterator()) {
            if (!iterator.hasNext()) {
                // Header unobservable without a data row; either way the file carries no payload.
                outcome.add(file, 1, "file \"" + file.fileName() + "\" is empty or has no header");
                return 0;
            }
            var first = iterator.next();
            var columns = GtfsStaticColumns.of(file, first.getHeader());
            if (!checkHeader(columns, outcome)) {
                return 0;
            }
            validateDimensionRow(columns, first, outcome, ids);
            rows++;
            while (iterator.hasNext()) {
                validateDimensionRow(columns, iterator.next(), outcome, ids);
                rows++;
            }
        } catch (CsvParseException e) {
            // Mirrors gtfs-validator csv_parsing_failed / invalid_row_length: the file stops here,
            // the remaining files still validate.
            outcome.add(file, 0, "CSV parsing failed in \"" + file.fileName() + "\": " + e.getMessage());
        }
        outcome.rows = rows;
        return rows;
    }

    private void validateDimensionRow(
            GtfsStaticColumns columns, NamedCsvRecord record, FileAccumulator outcome, ReferencedIds ids) {
        var line = record.getStartingLineNumber();
        switch (columns.file()) {
            case AGENCY ->
                reportViolations(
                        outcome, AGENCY, line, GtfsStaticValidators.AGENCY.validate(AgencyRow.from(record, columns)));
            case STOPS -> {
                var row = StopRow.from(record, columns);
                reportViolations(outcome, STOPS, line, GtfsStaticValidators.STOP.validate(row));
                registerPrimaryKey(outcome, STOPS, line, "stop_id", row.stopId(), ids.stops);
            }
            case ROUTES -> {
                var row = RouteRow.from(record, columns);
                reportViolations(outcome, ROUTES, line, GtfsStaticValidators.ROUTE.validate(row));
                registerPrimaryKey(outcome, ROUTES, line, "route_id", row.routeId(), ids.routes);
            }
            case TRIPS -> {
                var row = TripRow.from(record, columns);
                reportViolations(outcome, TRIPS, line, GtfsStaticValidators.TRIP.validate(row));
                registerPrimaryKey(outcome, TRIPS, line, "trip_id", row.tripId(), ids.trips);
                if (row.routeId() != null && !ids.routes.truncated() && !ids.routes.contains(row.routeId())) {
                    // routes.txt is streamed before trips.txt, so this reference is exact.
                    outcome.add(
                            TRIPS,
                            line,
                            "route_id",
                            "foreign key violation: route_id \"" + row.routeId() + "\" not found in routes.txt");
                }
            }
            // Reachable only if a non-dimension file is routed here, which the kind switch prevents.
            case STOP_TIMES, CALENDAR, CALENDAR_DATES ->
                throw new IllegalStateException("not a dimension file: " + columns.file());
        }
    }

    private long validateStopTimes(ZipFile zip, GtfsStaticFile file, FileAccumulator outcome, ReferencedIds ids)
            throws IOException {
        var entry = findEntry(zip, file);
        if (entry == null) {
            outcome.add(file, 0, "required file \"" + file.fileName() + "\" is missing from the archive");
            return 0;
        }
        outcome.present = true;

        long rows = 0;
        var sequence = new StopSequenceCheck(maxTrackedTrips);
        try (var records = open(zip, entry);
                var iterator = records.iterator()) {
            if (!iterator.hasNext()) {
                outcome.add(file, 1, "file \"" + file.fileName() + "\" is empty or has no header");
                return 0;
            }
            var first = iterator.next();
            var columns = GtfsStaticColumns.of(file, first.getHeader());
            if (!checkHeader(columns, outcome)) {
                return 0;
            }
            validateStopTimeRow(columns, first, outcome, ids, sequence);
            rows++;
            while (iterator.hasNext()) {
                validateStopTimeRow(columns, iterator.next(), outcome, ids, sequence);
                rows++;
            }
        } catch (CsvParseException e) {
            outcome.add(file, 0, "CSV parsing failed in \"" + file.fileName() + "\": " + e.getMessage());
        }
        outcome.complete &= sequence.complete();
        outcome.rows = rows;
        return rows;
    }

    private void validateStopTimeRow(
            GtfsStaticColumns columns,
            NamedCsvRecord record,
            FileAccumulator outcome,
            ReferencedIds ids,
            StopSequenceCheck sequence) {
        var line = record.getStartingLineNumber();
        var row = StopTimeRow.from(record, columns);
        reportViolations(outcome, STOP_TIMES, line, GtfsStaticValidators.STOP_TIME.validate(row));
        if (row.tripId() != null && !ids.trips.truncated() && !ids.trips.contains(row.tripId())) {
            outcome.add(
                    STOP_TIMES,
                    line,
                    "trip_id",
                    "foreign key violation: trip_id \"" + row.tripId() + "\" not found in trips.txt");
        }
        if (row.stopId() != null && !ids.stops.truncated() && !ids.stops.contains(row.stopId())) {
            outcome.add(
                    STOP_TIMES,
                    line,
                    "stop_id",
                    "foreign key violation: stop_id \"" + row.stopId() + "\" not found in stops.txt");
        }
        // Mirrors gtfs-validator decreasing_or_equal on stop_sequence: a decreasing sequence within a
        // trip is an error. The check keeps O(1) state for grouped feeds and only escalates when the
        // file proves not to be grouped.
        if (row.tripId() == null || row.stopSequence() == null) {
            return;
        }
        try {
            if (sequence.outOfOrder(row.tripId(), Long.parseLong(row.stopSequence()))) {
                outcome.add(
                        STOP_TIMES,
                        line,
                        "stop_sequence",
                        "stop_sequence decreases within trip \"" + row.tripId() + "\"");
            }
        } catch (NumberFormatException _) {
            // Malformed values are already reported by the row validator.
        }
    }

    private void validateServiceCoverage(Map<GtfsStaticFile, FileAccumulator> outcomes, ReferencedIds ids) {
        if (!calendarFilePresent(outcomes)) {
            // Mirrors gtfs-validator missing_calendar_and_calendar_date_files.
            outcomes.get(GtfsStaticFile.CALENDAR)
                    .add(
                            GtfsStaticFile.CALENDAR,
                            0,
                            "at least one of calendar.txt or calendar_dates.txt must be present");
            return;
        }
        if (ids.trips.size() > 0 && ids.services.size() == 0) {
            // Trips reference service ids that no calendar file defines.
            outcomes.get(GtfsStaticFile.CALENDAR)
                    .add(GtfsStaticFile.CALENDAR, 0, "service_id", "no service_id values found in calendar files");
        }
    }

    private static boolean calendarFilePresent(Map<GtfsStaticFile, FileAccumulator> outcomes) {
        return outcomes.get(GtfsStaticFile.CALENDAR).present || outcomes.get(GtfsStaticFile.CALENDAR_DATES).present;
    }

    private long readServiceIds(ZipFile zip, GtfsStaticFile file, FileAccumulator outcome, ReferencedIds ids)
            throws IOException {
        var entry = findEntry(zip, file);
        if (entry == null) {
            return 0;
        }
        outcome.present = true;
        long rows = 0;
        try (var records = open(zip, entry);
                var iterator = records.iterator()) {
            if (!iterator.hasNext()) {
                // An empty optional calendar file is not an error on its own: the pair check decides.
                return 0;
            }
            var first = iterator.next();
            var columns = GtfsStaticColumns.of(file, first.getHeader());
            if (!checkHeader(columns, outcome)) {
                return 0;
            }
            addServiceId(columns, first, ids);
            rows++;
            while (iterator.hasNext()) {
                addServiceId(columns, iterator.next(), ids);
                rows++;
            }
        } catch (CsvParseException e) {
            outcome.add(file, 0, "CSV parsing failed in \"" + file.fileName() + "\": " + e.getMessage());
        }
        outcome.rows = rows;
        return rows;
    }

    private static void addServiceId(GtfsStaticColumns columns, NamedCsvRecord record, ReferencedIds ids) {
        var serviceId = columns.value(record, "service_id");
        if (serviceId != null) {
            // A service_id may legitimately appear in both calendar files, so a repeat is not a
            // duplicate-key violation; the index only answers later "was it seen" questions.
            ids.services.add(serviceId);
        }
    }

    private ZipEntry findEntry(ZipFile zip, GtfsStaticFile file) {
        // Archives sometimes nest the files under a directory; match on the leaf name.
        var name = file.fileName();
        var direct = zip.getEntry(name);
        if (direct != null) {
            return direct;
        }
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            var path = entry.getName();
            if (path.endsWith("/" + name) || path.endsWith("\\" + name)) {
                return entry;
            }
        }
        return null;
    }

    private CsvReader<NamedCsvRecord> open(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        var handedOver = false;
        try {
            // IGNORE keeps ragged rows flowing: a short row must be reported by the row validators,
            // not abort the file. STRICT would throw on the first mismatch.
            var reader = CsvReader.builder()
                    .detectBomHeader(true)
                    .extraFieldStrategy(FieldMismatchStrategy.IGNORE)
                    .missingFieldStrategy(FieldMismatchStrategy.IGNORE)
                    .ofNamedCsvRecord(in, StandardCharsets.UTF_8);
            handedOver = true;
            return reader;
        } finally {
            if (!handedOver) {
                in.close();
            }
        }
    }

    private boolean checkHeader(GtfsStaticColumns columns, FileAccumulator outcome) {
        var names = columns.names();
        var missing = new ArrayList<String>();
        for (var required : columns.file().requiredColumns()) {
            if (!names.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            // Mirrors gtfs-validator missing_required_column.
            outcome.add(
                    columns.file(),
                    1,
                    String.join(",", missing),
                    "missing required columns in \"" + columns.file().fileName() + "\": " + missing);
            return false;
        }
        var seen = new HashSet<String>();
        for (var name : names) {
            if (!seen.add(name)) {
                // Mirrors gtfs-validator duplicated_column.
                outcome.add(columns.file(), 1, name, "duplicated column \"" + name + "\" in header");
            }
            if (name.isBlank()) {
                // Mirrors gtfs-validator empty_column_name.
                outcome.add(columns.file(), 1, name, "empty column name in header");
            }
        }
        return true;
    }

    private void reportViolations(
            FileAccumulator outcome, GtfsStaticFile file, long line, List<ConstraintViolation> violations) {
        for (var violation : violations) {
            outcome.add(file, line, violation.name(), violation.message());
        }
    }

    private void registerPrimaryKey(
            FileAccumulator outcome, GtfsStaticFile file, long line, String field, String id, HashedIdIndex index) {
        if (id == null) {
            // A missing id is the row validator's problem, not something the index can resolve.
            return;
        }
        var slot = index.add(id);
        if (slot == HashedIdIndex.IdSlot.PRESENT) {
            outcome.add(file, line, field, "duplicate key: " + field + " \"" + id + "\" appears more than once");
        }
    }

    /** Ids collected from the dimension pass, used for referential checks in later files. */
    private static final class ReferencedIds {
        final HashedIdIndex stops;

        final HashedIdIndex routes;

        final HashedIdIndex trips;

        final HashedIdIndex services;

        ReferencedIds(int maxTrackedIds) {
            this.stops = new HashedIdIndex(maxTrackedIds);
            this.routes = new HashedIdIndex(maxTrackedIds);
            this.trips = new HashedIdIndex(maxTrackedIds);
            this.services = new HashedIdIndex(maxTrackedIds);
        }

        /** Whether any index hit its entry limit, making referential checks incomplete. */
        boolean anyTruncated() {
            return stops.truncated() || routes.truncated() || trips.truncated() || services.truncated();
        }
    }
}
