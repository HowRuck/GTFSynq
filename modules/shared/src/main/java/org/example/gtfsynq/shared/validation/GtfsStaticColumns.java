package org.example.gtfsynq.shared.validation;

import de.siegmar.fastcsv.reader.NamedCsvRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Column name to index mapping for one GTFS file, resolved once per header.
 *
 * <p>FastCSV resolves a field by name with a linear scan of the header plus an {@code Optional}
 * allocation, so resolving names per cell would repeat that work for every row of a multi-million
 * row file. Lookups here are a single map hit against a precomputed index; values are then read
 * positionally via {@link NamedCsvRecord#getField(int)}.
 *
 * <p>Blank values normalize to {@code null} and non-blank values are stripped, so validators treat
 * absent and blank uniformly. Values are never {@code null} from FastCSV; absent columns and short
 * rows both read as {@code null}.
 */
public final class GtfsStaticColumns {

    private final GtfsStaticFile file;

    private final List<String> names;

    private final Map<String, Integer> index;

    private GtfsStaticColumns(GtfsStaticFile file, List<String> names, Map<String, Integer> index) {
        this.file = file;
        this.names = names;
        this.index = index;
    }

    /**
     * Builds the mapping for a file, stripping each header cell.
     *
     * @param file file the header belongs to, used for diagnostics and required-column checks
     * @param header raw header cells in file order, duplicates preserved
     */
    public static GtfsStaticColumns of(GtfsStaticFile file, List<String> header) {
        var names = new ArrayList<String>(header.size());
        var index = new HashMap<String, Integer>(header.size());
        for (var i = 0; i < header.size(); i++) {
            var name = strip(header.get(i));
            names.add(name);
            // First occurrence wins, matching FastCSV's own duplicate-header behaviour.
            index.putIfAbsent(name, i);
        }
        return new GtfsStaticColumns(file, List.copyOf(names), Map.copyOf(index));
    }

    /** File this mapping was built for. */
    public GtfsStaticFile file() {
        return file;
    }

    /** Header cells as they appear in the file, after stripping and BOM removal. */
    public List<String> names() {
        return names;
    }

    /**
     * Reads a column, returning {@code null} when the column is absent, the row is too short, or the
     * cell is blank.
     */
    public String value(NamedCsvRecord record, String column) {
        var at = index.get(column);
        if (at == null || at >= record.getFieldCount()) {
            return null;
        }
        var value = strip(record.getField(at));
        return value.isEmpty() ? null : value;
    }

    private static String strip(String value) {
        var stripped = value.strip();
        // FastCSV strips the BOM from the first header cell only when detectBomHeader is on; feeds
        // carrying one on a later cell would otherwise poison every lookup of that column.
        return stripped.isEmpty() || stripped.charAt(0) != '\uFEFF'
                ? stripped
                : stripped.substring(1).strip();
    }
}
