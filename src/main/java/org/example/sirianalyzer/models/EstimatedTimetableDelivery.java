package org.example.sirianalyzer.models;

import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Delivery containing estimated timetable information
 */
@Getter
@Setter
@NoArgsConstructor
@JsonRootName("EstimatedTimetableDelivery")
public class EstimatedTimetableDelivery {

    /** Version of the estimated timetable delivery specification (XML attribute "version") */
    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version;

    /** Timestamp when this specific delivery was generated (SIRI: ResponseTimestamp) */

    private String responseTimestamp;

    /** Frame containing one or more estimated vehicle journeys (SIRI: EstimatedJourneyVersionFrame) */
    private EstimatedJourneyVersionFrame estimatedJourneyVersionFrame;
}
