package org.example.gtfsynq.shared.util;

import io.arxila.javatuples.Pair;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Utility class for formatting GTFS feed data
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GtfsFeedFormatter {

    /**
     * Parses a GTFS date string into a LocalDate
     *
     * @param date the date string
     * @return the parsed LocalDate, or null if the input is null or blank
     */
    public static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(date, GTFS_DATE);
        } catch (Exception _) {
            return LocalDate.parse(date);
        }
    }

    /**
     * Parses a GTFS time string into a LocalTime
     *
     * @param time the time string
     * @return the parsed LocalTime, or null if the input is null or blank
     */
    public static Pair<LocalTime, Short> parseTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }

        try {
            return Pair.of(LocalTime.parse(time, GTFS_TIME), (short) 0);
        } catch (Exception _) {
            var parts = time.split(":");

            var hours = Integer.parseInt(parts[0]);
            var minutes = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            var seconds = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            var dayOffset = (short) (hours / 24);
            hours = hours % 24;

            return Pair.of(LocalTime.of(hours, minutes, seconds), dayOffset);
        }
    }

    /**
     * Parses a GTFS date/time string into an Instant
     *
     * @param dateTime the date/time string
     * @return the parsed Instant, or null if the input is null
     */
    public static Instant parseDateTime(Long dateTime) {
        return (dateTime == null) ? null : Instant.ofEpochSecond(dateTime);
    }

    /**
     * Returns the input string if present, or null if not present
     *
     * @param present whether the value is present
     * @param value   the value to return if present
     * @return the input string if present, or null if not present
     */
    public static String nullableString(boolean present, String value) {
        return present ? value : null;
    }

    /**
     * Returns the input integer if present, or null if not present
     *
     * @param present whether the value is present
     * @param value   the value to return if present
     * @return the input integer if present, or null if not present
     */
    public static Integer nullableInteger(boolean present, int value) {
        return present ? value : null;
    }

    /**
     * Returns the input LocalDate if present, or null if not present
     *
     * @param present whether the value is present
     * @param value   the value to return if present
     * @return the input LocalDate if present, or null if not present
     */
    public static LocalDate nullableDate(boolean present, String value) {
        return present ? parseDate(value) : null;
    }

    /**
     * Returns the input LocalTime if present, or null if not present
     *
     * @param present whether the value is present
     * @param value   the value to return if present
     * @return the input LocalTime if present, or null if not present
     */
    public static Pair<LocalTime, Short> nullableTime(boolean present, String value) {
        return present ? parseTime(value) : null;
    }

    /**
     * Returns the input Instant if present, or null if not present
     *
     * @param present whether the value is present
     * @param value   the value to return if present
     * @return the input Instant if present, or null if not present
     */
    public static Instant nullableInstant(boolean present, Long value) {
        return present ? parseDateTime(value) : null;
    }

    /**
     * Builds a key string from the given parts, separated by colons
     *
     * @param parts the parts to join
     * @return the joined key string
     */
    public static String buildKey(String... parts) {
        return String.join(":", parts);
    }

    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter GTFS_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
}
