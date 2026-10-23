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

    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String[] BYTE_UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private UiFormat() {}

    public static String dateTime(Timestamp value) {
        if (!isSet(value)) {
            return DASH;
        }
        return DATE_TIME.format(instant(value).atZone(ZoneId.systemDefault()));
    }

    /** Time of day for a timestamp; stop times share the service date and do not repeat it. */
    public static String time(Timestamp value) {
        if (!isSet(value)) {
            return DASH;
        }
        return TIME_OF_DAY.format(instant(value).atZone(ZoneId.systemDefault()));
    }

    /**
     * The predicted time of a stop event, falling back to the scheduled time plus the feed delay.
     *
     * <p>Many feeds only send delays; deriving the clock time keeps the column useful for them.
     */
    public static String eventTime(Timestamp predicted, Timestamp scheduled, Integer delaySeconds) {
        if (isSet(predicted)) {
            return time(predicted);
        }
        if (isSet(scheduled) && delaySeconds != null) {
            var shifted = Timestamp.newBuilder()
                    .setSeconds(scheduled.getSeconds() + delaySeconds)
                    .setNanos(scheduled.getNanos())
                    .build();
            return time(shifted);
        }
        return DASH;
    }

    /** Delay formatted as {@code +minutes:seconds}; the sign shows early and late events. */
    public static String delay(Integer seconds) {
        if (seconds == null) {
            return DASH;
        }
        var absolute = Math.abs((long) seconds);
        return String.format(Locale.ROOT, "%s%d:%02d", seconds < 0 ? "-" : "+", absolute / 60, absolute % 60);
    }

    private static boolean isSet(Timestamp value) {
        return value != null && !(value.getSeconds() == 0 && value.getNanos() == 0);
    }

    private static Instant instant(Timestamp value) {
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
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
