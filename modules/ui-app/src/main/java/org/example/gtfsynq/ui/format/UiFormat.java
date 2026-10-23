package org.example.gtfsynq.ui.format;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Presentation helpers shared by the table columns and the stat cards. */
public final class UiFormat {

    private static final String DASH = "—";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] BYTE_UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private UiFormat() {}

    public static String dateTime(Timestamp value) {
        if (value == null || (value.getSeconds() == 0 && value.getNanos() == 0)) {
            return DASH;
        }
        return DATE_TIME.format(
                Instant.ofEpochSecond(value.getSeconds(), value.getNanos()).atZone(ZoneId.systemDefault()));
    }

    public static String text(String value) {
        return value == null || value.isBlank() ? DASH : value;
    }

    /** GTFS times may exceed 24:00:00, so only the first 8 characters are shown. */
    public static String time(String value) {
        if (value == null || value.isBlank()) {
            return DASH;
        }
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    public static String bytes(long value) {
        double size = value;
        int unit = 0;
        while (size >= 1024 && unit < BYTE_UNITS.length - 1) {
            size /= 1024;
            unit++;
        }
        return (unit == 0 ? String.format(Locale.ROOT, "%.0f", size) : String.format(Locale.ROOT, "%.1f", size)) + " "
                + BYTE_UNITS[unit];
    }

    public static String shortHash(String value) {
        return value == null || value.isBlank() ? DASH : value.substring(0, Math.min(12, value.length())) + "…";
    }

    public static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
