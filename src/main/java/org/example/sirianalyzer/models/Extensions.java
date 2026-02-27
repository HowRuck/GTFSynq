package org.example.sirianalyzer.models;

/**
 * Represents extensions to SIRI elements that may contain additional vendor-specific or
 * domain-specific information. This is typically used for custom fields that are not
 * part of the standard SIRI schema.
 *
 * Field-to-XML mapping is handled by the XmlMapper's UPPER_CAMEL_CASE property naming strategy
 * configured in `SiriParserService`, so per-field Jackson annotations have been removed.
 */
public record Extensions(
    /** Indicates if the stop is at an airport */
    Boolean stopsAtAirport
) {}
