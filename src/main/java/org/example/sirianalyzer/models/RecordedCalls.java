package org.example.sirianalyzer.models;

import java.util.List;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Container for a list of recorded calls (completed stops) along a vehicle journey.
 * Each recorded call represents a stop that has already been served.
 */
public record RecordedCalls(
    /** List of recorded calls in sequence order */
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "RecordedCall")
    List<RecordedCall> recordedCall
) {}
