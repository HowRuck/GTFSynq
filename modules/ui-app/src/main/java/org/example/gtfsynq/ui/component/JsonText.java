package org.example.gtfsynq.ui.component;

/**
 * Minimal JSON writing shared by the map elements.
 *
 * <p>The UI deliberately keeps a serialiser out of its dependencies: the pushed payloads are a few
 * arrays of numbers and strings, so quoting them by hand is cheaper than pulling Jackson into the
 * browser-facing module.
 */
final class JsonText {

    private JsonText() {}

    /**
     * Six decimals are about 0.1 m, far below what a basemap can show, and noticeably shorter in the
     * JSON than the full double representation.
     */
    static String coordinate(double value) {
        var rounded = Math.round(value * 1_000_000) / 1_000_000.0;
        return Double.toString(rounded);
    }

    /** Quotes a string for JSON, or writes {@code null}. */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        var quoted = new StringBuilder(value.length() + 2).append('"');
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
