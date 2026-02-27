package org.example.sirianalyzer.models;

/**
 * Represents a via point in a journey, typically used to indicate intermediate stops
 * or routing information between origin and destination.
 *
 * Field-to-XML mapping is handled by the XmlMapper's UPPER_CAMEL_CASE property naming strategy
 * configured in `SiriParserService`, so per-field Jackson annotations have been removed.
 */
public record Via(
    /** Name of the place that the journey passes through */
    String placeName
) {}
