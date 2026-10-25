package org.example.gtfsynq.ui.format;

import com.google.protobuf.Timestamp;
import com.vaadin.flow.component.UI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Presentation helpers shared by the table columns and the stat cards. */
public final class UiFormat {

    /** Placeholder for a value the feed does not provide. */
    public static final String NOT_AVAILABLE = "N/A";

    /**
     * Longest identifier shown in a grid cell; feed ids such as {@code
     * BRA:ServiceJourney:6228_260630061449169_1060} would otherwise stretch their column across the
     * table.
     */
    private static final int MAX_ID_LENGTH = 32;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Fallback for timestamps rendered outside a browser request: Central European time, the region
     * the feeds cover. {@code Europe/Berlin} is used rather than the fixed {@code CEST} offset so
     * the value follows the CET/CEST switch instead of drifting an hour in winter.
     */
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Berlin");

    private static final String[] BYTE_UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private UiFormat() {}

    /**
     * Time zone used for every rendered timestamp: the user's own, as reported by the browser in
     * the extended client details Vaadin collects during UI initialization.
     *
     * <p>Falls back to {@link #FALLBACK_ZONE} when there is no current UI (background work, tests)
     * or when the browser has not reported a zone yet, so a cell never renders in the JVM default
     * zone, which is UTC in the container.
     */
    public static ZoneId displayZone() {
        var ui = UI.getCurrent();
        if (ui == null) {
            return FALLBACK_ZONE;
        }
        var details = ui.getPage().getExtendedClientDetails();
        // Before the browser reports its details the placeholder carries no zone id and a zero
        // offset, which getZoneId() would resolve to UTC.
        if (details.getTimeZoneId() == null && details.getTimezoneOffset() == 0) {
            return FALLBACK_ZONE;
        }
        return details.getZoneId();
    }

    public static String dateTime(Timestamp value) {
        if (!isSet(value)) {
            return NOT_AVAILABLE;
        }
        return DATE_TIME.format(instant(value).atZone(displayZone()));
    }

    /** Time of day for a timestamp; stop times share the service date and do not repeat it. */
    public static String time(Timestamp value) {
        if (!isSet(value)) {
            return NOT_AVAILABLE;
        }
        return TIME_OF_DAY.format(instant(value).atZone(displayZone()));
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
        // No live prediction for this stop: fall back to the static schedule, which is what the
        // merged live-trip view shows for stops the feed has not updated.
        if (isSet(scheduled)) {
            return time(scheduled);
        }
        return NOT_AVAILABLE;
    }

    /** Delay formatted as {@code +minutes:seconds}; the sign shows early and late events. */
    public static String delay(Integer seconds) {
        if (seconds == null) {
            return NOT_AVAILABLE;
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
        return value == null || value.isBlank() ? NOT_AVAILABLE : value;
    }

    /** GTFS times may exceed 24:00:00, so only the first 8 characters are shown. */
    public static String time(String value) {
        if (value == null || value.isBlank()) {
            return NOT_AVAILABLE;
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
        return value == null || value.isBlank()
                ? NOT_AVAILABLE
                : value.substring(0, Math.min(12, value.length())) + "…";
    }

    /**
     * Shortens an identifier to {@link #MAX_ID_LENGTH} characters so one long id cannot widen its
     * column, marking the cut with an ellipsis. Callers keep the full value in the cell tooltip.
     */
    public static String elideId(String value) {
        if (value == null || value.isBlank()) {
            return NOT_AVAILABLE;
        }
        if (value.length() <= MAX_ID_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ID_LENGTH - 1) + "…";
    }

    public static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
