package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents extensions to SIRI elements that may contain additional vendor-specific or
 * domain-specific information. This is typically used for custom fields that are not
 * part of the standard SIRI schema.
 *
 * Field-to-XML mapping is handled by the XmlMapper's UPPER_CAMEL_CASE property naming strategy
 * configured in `SiriParserService`, so per-field Jackson annotations have been removed.
 */
@Getter
@Setter
@NoArgsConstructor
public class Extensions {

    // This class serves as a container for any extension elements that may appear
    // in SIRI messages. The actual extension elements will be mapped to fields
    // based on the UPPER_CAMEL_CASE naming strategy.

    /** Indicates if the stop is at an airport */
    private Boolean stopsAtAirport;
}
