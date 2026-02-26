package org.example.sirianalyzer;

import java.util.Locale;

/**
 * Utility class for formatting byte sizes into human-readable strings.
 * Provides methods to convert byte counts into more readable formats such as KiB, MiB, and GiB.
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
     * @return A human-readable string representation of the input bytes using binary prefixes
     */
    public static String humanBytes(int bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";

        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format(Locale.ROOT, "%.1f KiB", kib);

        double mib = kib / 1024.0;
        if (mib < 1024) return String.format(Locale.ROOT, "%.1f MiB", mib);

        double gib = mib / 1024.0;
        return String.format(Locale.ROOT, "%.1f GiB", gib);
    }
}