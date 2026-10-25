package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.example.gtfsynq.shared.validation.GtfsStaticRows.StopTimeRow;
import org.junit.jupiter.api.Test;

class StopTimeCopyEncoderTest {

    @Test
    void quotesStringsAndLeavesNullsAsUnquotedEmptyFields() {
        var out = new StringBuilder();

        StopTimeCopyEncoder.appendRow(out, "gtfs-de", row("1", "stop-1", null));

        var fields = parse(out.toString());
        assertEquals(23, fields.size());
        assertEquals("gtfs-de", fields.get(0));
        assertEquals("trip-1", fields.get(1));
        assertEquals("1", fields.get(2));
        assertEquals("stop-1", fields.get(3));
        assertEquals("20:44:00", fields.get(4));
        assertEquals("0", fields.get(5));
        assertEquals("", fields.get(6), "a missing departure time is NULL");
        assertEquals("", fields.get(7));
        assertEquals("", fields.get(8), "a missing pickup type is NULL");
    }

    @Test
    void splitsGtfsTimesPastMidnightIntoTimeAndOverflowDays() {
        var out = new StringBuilder();

        StopTimeCopyEncoder.appendRow(out, "feed", row("1", "A", "25:10:05"));

        var fields = parse(out.toString());
        assertEquals("20:44:00", fields.get(4));
        assertEquals("0", fields.get(5));
        assertEquals("01:10:05", fields.get(6));
        assertEquals("1", fields.get(7));
    }

    @Test
    void escapesQuotesAndCommasInNames() {
        var out = new StringBuilder();
        var stopId = "He said \"hi\", once";

        StopTimeCopyEncoder.appendRow(out, "feed", row("1", stopId, null));

        var fields = parse(out.toString());
        assertEquals(23, fields.size());
        assertEquals(stopId, fields.get(3));
    }

    @Test
    void rejectsAMissingStopSequence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StopTimeCopyEncoder.appendRow(new StringBuilder(), "feed", row(null, "A", null)));
    }

    /** Minimal CSV parser for the COPY dialect: quoted fields, doubled quotes, unquoted empty = null. */
    private static List<String> parse(String line) {
        var fields = new ArrayList<String>();
        var field = new StringBuilder();
        var quoted = false;
        for (var i = 0; i < line.length(); i++) {
            var c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c != '\n') {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static StopTimeRow row(String sequence, String stopId, String departure) {
        return new StopTimeRow(
                "trip-1",
                "20:44:00",
                departure,
                stopId,
                sequence,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
