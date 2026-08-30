package org.example.gtfsynq.shared.util;

import java.util.Locale;

/**
 * Utility class for formatting byte sizes into human-readable strings.
 * Provides methods to convert byte counts into more readable formats such as
 * KiB, MiB, and GiB.
 */
public final class SizeFormat {

    private SizeFormat() {}

    /**
     * Converts a byte count into a human-readable string format
     * <p/>
     * The conversion uses binary prefixes, where:
     * 1024 bytes = 1 KiB, 1024 KiB = 1 MiB, 1024 MiB = 1 GiB
     *
     * @param bytes The number of bytes to convert
     * @return A human-readable string representation of the input bytes using
     *         binary prefixes
     */
    public static String humanBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";

        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);

        double mib = kib / 1024.0;
        if (mib < 1024) return String.format(Locale.ROOT, "%.1f MiB", mib);

        double gib = mib / 1024.0;
        return String.format(Locale.ROOT, "%.1f GiB", gib);
    }

    /**
     * Formats a number with a suffix (K, M, B) depending on its magnitud.
     *
     * @param value The number to format
     * @return A human-readable string representation of the input number
     */
    public static String formatNumber(long value) {
        long abs = Math.abs(value);

        int magnitude =
                switch (abs == 0 ? 0 : (int) Math.log10(abs) / 3) {
                    case 0 -> 0; // < 1K
                    case 1 -> 1; // K
                    case 2 -> 2; // M
                    default -> 3; // B+
                };

        double scaled =
                switch (magnitude) {
                    case 1 -> value / 1_000.0;
                    case 2 -> value / 1_000_000.0;
                    case 3 -> value / 1_000_000_000.0;
                    default -> value;
                };

        String suffix =
                switch (magnitude) {
                    case 1 -> "K";
                    case 2 -> "M";
                    case 3 -> "B";
                    default -> "";
                };

        return magnitude == 0 ? String.valueOf(value) : String.format("%.1f%s", scaled, suffix);
    }
}
