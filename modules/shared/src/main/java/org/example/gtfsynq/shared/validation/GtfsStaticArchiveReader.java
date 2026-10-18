package org.example.gtfsynq.shared.validation;

import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.AgencyRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.CalendarDateRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.CalendarRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.RouteRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.TripRow;

/**
 * Streaming reader for GTFS static archives.
 *
 * <p>Where {@link GtfsStaticArchiveValidator} answers "may this archive be trusted", this hands the
 * rows out for storage. It walks the archive the same way - {@link ZipFile} per entry, FastCSV per
 * record, rows mapped through a {@link GtfsStaticColumns} index - and streams each file in batches,
 * so a consumer can write rows out as they arrive instead of materialising a multi-million row
 * {@code stop_times.txt} in memory.
 *
 * <p>Files are read in {@link GtfsStaticFile} declaration order, which puts the dimension files
 * before {@code stop_times} so a consumer that writes rows as it goes never has to defer a
 * reference to a row it has not written yet. Absent files are skipped without a callback; the
 * calendar pair is the only one where that is expected, since at least one of the two is optional.
 *
 * <p>Cells are handed over as read, blanks normalised to {@code null}: the row shapes are the same
 * ones the validator checks, so this is not a parsing variant of the validation pass but the
 * results of it. Callers that need typed values parse them at the boundary they store them.
 */
public final class GtfsStaticArchiveReader {

    /** Rows handed to a sink per callback; sized so a batch costs a single JDBC round trip. */
    public static final int DEFAULT_BATCH_SIZE = 5000;

    /** Receives the rows of one archive, one batch per callback, in {@code GtfsStaticFile} order. */
    public interface StaticRowSink {

        void onAgency(List<AgencyRow> rows);

        void onStops(List<StopRow> rows);

        void onRoutes(List<RouteRow> rows);

        void onTrips(List<TripRow> rows);

        void onStopTimes(List<StopTimeRow> rows);

        void onCalendar(List<CalendarRow> rows);

        void onCalendarDates(List<CalendarDateRow> rows);
    }

    /** Maps one CSV record onto a row shape, using the column index built for its file. */
    @FunctionalInterface
    private interface RowMapper<T> {
        T map(NamedCsvRecord record, GtfsStaticColumns columns);
    }

    /** Reads an archive with {@link #DEFAULT_BATCH_SIZE}. */
    public void read(Path archive, StaticRowSink sink) throws IOException {
        read(archive, DEFAULT_BATCH_SIZE, sink);
    }

    /**
     * Reads an archive, handing each file's rows to the sink in batches.
     *
     * @param archive staged GTFS zip on local disk
     * @param batchSize rows per callback, must be positive
     * @param sink receives the batches, called only for files present in the archive
     * @throws IOException if the archive cannot be opened or an entry cannot be read
     */
    public void read(Path archive, int batchSize, StaticRowSink sink) throws IOException {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        try (var zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            for (var file : GtfsStaticFile.values()) {
                switch (file) {
                    case AGENCY -> read(zip, file, batchSize, AgencyRow::from, sink::onAgency);
                    case STOPS -> read(zip, file, batchSize, StopRow::from, sink::onStops);
                    case ROUTES -> read(zip, file, batchSize, RouteRow::from, sink::onRoutes);
                    case TRIPS -> read(zip, file, batchSize, TripRow::from, sink::onTrips);
                    case STOP_TIMES -> read(zip, file, batchSize, StopTimeRow::from, sink::onStopTimes);
                    case CALENDAR -> read(zip, file, batchSize, CalendarRow::from, sink::onCalendar);
                    case CALENDAR_DATES -> read(zip, file, batchSize, CalendarDateRow::from, sink::onCalendarDates);
                }
            }
        }
    }

    /** Streams one entry, batching rows so neither side has to hold a whole file. */
    private <T> void read(
            ZipFile zip, GtfsStaticFile file, int batchSize, RowMapper<T> mapper, Consumer<List<T>> consumer)
            throws IOException {
        var entry = GtfsStaticEntries.find(zip, file);
        if (entry == null) {
            return;
        }

        var batch = new ArrayList<T>(batchSize);
        try (CsvReader<NamedCsvRecord> records = GtfsStaticEntries.open(zip, entry)) {
            var iterator = records.iterator();
            if (!iterator.hasNext()) {
                // Header only, or nothing at all: the file carries no rows, so the sink stays uncalled.
                return;
            }
            var first = iterator.next();
            var columns = GtfsStaticColumns.of(file, first.getHeader());
            batch.add(mapper.map(first, columns));
            while (iterator.hasNext()) {
                var row = mapper.map(iterator.next(), columns);
                batch.add(row);
                if (batch.size() >= batchSize) {
                    consumer.accept(List.copyOf(batch));
                    batch.clear();
                }
            }
        } catch (CsvParseException e) {
            throw new IOException("could not read \"" + file.fileName() + "\" from the archive", e);
        }

        if (!batch.isEmpty()) {
            consumer.accept(List.copyOf(batch));
        }
    }
}
