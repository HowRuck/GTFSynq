package org.example.sirianalyzer.models;

import java.util.List;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * A frame (snapshot) of estimated journey information at a specific point in time.
 * Groups related estimated vehicle journeys together.
 *
 * SIRI element mapping for this frame:
 * - recordedAtTime                 -> RecordedAtTime
 * - estimatedVehicleJourneys (list) -> EstimatedVehicleJourney (elements are unwrapped; no enclosing list element)
 *
 * Notes:
 * - The RecordedAtTime element is the SIRI name for the timestamp when the frame was captured.
 * - EstimatedVehicleJourney elements are provided as a sequence of elements (no wrapper element),
 *   therefore the list is annotated with {@code @JacksonXmlElementWrapper(useWrapping = false)} and
 *   each element is mapped to the SIRI element name "EstimatedVehicleJourney".
 */
public record EstimatedJourneyVersionFrame(
    /** Timestamp when this journey information was recorded/captured */
    String recordedAtTime,

    /** Detailed information about a specific estimated vehicle journey */
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "EstimatedVehicleJourney")
    List<EstimatedVehicleJourney> estimatedVehicleJourneys
) {}
