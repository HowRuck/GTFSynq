package org.example.gtfsynq.shared.validation;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.FieldMismatchStrategy;
import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Access to the GTFS files inside a staged archive, shared by the validator and the reader.
 *
 * <p>Both passes walk the same ZIP with the same parser settings, so entry lookup and reader
 * configuration live here rather than being written twice: a feed that validates under one set of
 * rules must be read under exactly the same ones.
 */
final class GtfsStaticEntries {

    private GtfsStaticEntries() {}

    /**
     * Finds a GTFS file inside an archive, or {@code null} when it is absent.
     *
     * <p>Archives sometimes nest the files under a directory, so a miss on the entry name falls back
     * to matching the leaf name at the end of an entry path.
     */
    static ZipEntry find(ZipFile zip, GtfsStaticFile file) {
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

    /**
     * Opens a CSV entry as named records, with the caller owning the returned reader and its stream.
     *
     * <p>IGNORE keeps ragged rows flowing: a short row must be reported by the row checks, not abort
     * the file. STRICT would throw on the first mismatch.
     */
    static CsvReader<NamedCsvRecord> open(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        var handedOver = false;
        try {
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
}
