package org.example.sirianalyzer.models;

import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Container for a list of recorded calls (completed stops) along a vehicle journey.
 * Each recorded call represents a stop that has already been served.
 */
@Getter
@Setter
@NoArgsConstructor
public class RecordedCalls {

    /** List of recorded calls in sequence order */
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "RecordedCall")
    private List<RecordedCall> recordedCall;
}
