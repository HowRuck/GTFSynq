package org.example.sirianalyzer.models;

import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

/**
 * Container for a list of estimated calls (stops) along a vehicle journey.
 * Each call represents a stop with estimated arrival/departure times.
 *
 * The list is configured as unwrapped (elements appear without an enclosing list element).
 * Each element is explicitly mapped to the XML element name "EstimatedCall".
 */
@Getter
@Setter
@NoArgsConstructor
public class EstimatedCalls {

    /** List of estimated calls in sequence order */
    @JacksonXmlElementWrapper(useWrapping = false)
    private List<EstimatedCall> estimatedCall;
}
