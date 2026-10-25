package org.example.gtfsynq.store.adapter.outbound.database;

import java.util.Locale;
import org.example.gtfsynq.shared.util.GtfsFeedFormatter;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;

/**
 * Encodes a {@code static_stop_times} row as one CSV line for PostgreSQL {@code COPY ... FROM STDIN
 * WITH (FORMAT csv)}.
 *
 * <p>This exists because the table carries 23 columns: a 5,000-row insert batch exceeds the
 * protocol's 65,535 bind-parameter limit, so the driver has to split it, and maintaining the trigram
 * indexes row-by-row makes a multi-million-row feed take minutes. COPY bypasses both.
 *
 * <p>CSV conventions used by PostgreSQL: an unquoted empty field is {@code NULL}, a quoted empty
 * field is an empty string, and quotes inside a quoted field are doubled.
 */
final class StopTimeCopyEncoder {

    private StopTimeCopyEncoder() {}

    /**
     * Appends one row, in the column order of {@code StaticFeedWriter.STOP_TIMES_COPY_SQL}.
     *
     * @param out    target buffer
     * @param feedId feed the revision belongs to
     * @param row    row read from the archive
     */
    static void appendRow(StringBuilder out, String feedId, StopTimeRow row) {
        out.append(quote(feedId)).append(',');
        out.append(quote(row.tripId())).append(',');
        out.append(requiredInteger(row.stopSequence(), "stop_sequence")).append(',');
        out.append(quote(row.stopId())).append(',');

        appendTime(out, row.arrivalTime());
        out.append(',');
        appendTime(out, row.departureTime());
        out.append(',');

        appendShort(out, row.pickupType());
        out.append(',');
        appendShort(out, row.dropOffType());
        out.append(',');
        appendDouble(out, row.shapeDistTraveled());
        out.append(',');
        appendShort(out, row.timepoint());
        out.append(',');

        out.append(quote(row.locationGroupId())).append(',');
        out.append(quote(row.locationId())).append(',');
        out.append(quote(row.stopHeadsign())).append(',');

        appendTime(out, row.startPickupDropOffWindow());
        out.append(',');
        appendTime(out, row.endPickupDropOffWindow());
        out.append(',');

        appendShort(out, row.continuousPickup());
        out.append(',');
        appendShort(out, row.continuousDropOff());
        out.append(',');

        out.append(quote(row.pickupBookingRuleId())).append(',');
        out.append(quote(row.dropOffBookingRuleId()));
        out.append('\n');
    }

    /** In a GTFS time may exceed 24:00:00; the database splits it into a time and overflow days. */
    private static void appendTime(StringBuilder out, String value) {
        var parsed = value == null ? null : GtfsFeedFormatter.parseTime(value);
        if (parsed == null) {
            // Two fields (time and overflow) must still be emitted; otherwise every later column
            // shifts left on rows without a time.
            out.append(',');
            return;
        }
        var time = parsed.value0();
        out.append(String.format(Locale.ROOT, "%02d:%02d:%02d", time.getHour(), time.getMinute(), time.getSecond()));
        out.append(',');
        out.append(parsed.value1());
    }

    private static void appendShort(StringBuilder out, String value) {
        if (value != null && !value.isBlank()) {
            out.append(Short.parseShort(value.trim()));
        }
    }

    private static void appendDouble(StringBuilder out, String value) {
        if (value != null && !value.isBlank()) {
            out.append(Double.parseDouble(value.trim()));
        }
    }

    private static int requiredInteger(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return Integer.parseInt(value.trim());
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
