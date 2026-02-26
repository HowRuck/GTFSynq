package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a via point in a journey, typically used to indicate intermediate stops
 * or routing information between origin and destination.
 *
 * Field-to-XML mapping is handled by the XmlMapper's UPPER_CAMEL_CASE property naming strategy
 * configured in `SiriParserService`, so per-field Jackson annotations have been removed.
 */
@Getter
@Setter
@NoArgsConstructor
public class Via {

    /** Name of the place that the journey passes through */
    private String placeName;
}
