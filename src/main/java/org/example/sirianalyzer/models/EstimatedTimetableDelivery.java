package org.example.sirianalyzer.models;

import com.fasterxml.jackson.annotation.JsonRootName;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Delivery containing estimated timetable information
 */
@JsonRootName("EstimatedTimetableDelivery")
public record EstimatedTimetableDelivery(
    /** Version of the estimated timetable delivery specification (XML attribute "version") */
    @JacksonXmlProperty(isAttribute = true, localName = "version")
    String version,

    /** Timestamp when this specific delivery was generated (SIRI: ResponseTimestamp) */
    String responseTimestamp,

    /** Frame containing one or more estimated vehicle journeys (SIRI: EstimatedJourneyVersionFrame) */
    EstimatedJourneyVersionFrame estimatedJourneyVersionFrame
) {}
