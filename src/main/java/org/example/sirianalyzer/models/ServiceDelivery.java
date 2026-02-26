package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Service delivery container that wraps one or more specific delivery types.
 * Contains metadata about the response and the actual delivery payload.
 */
@Getter
@Setter
@NoArgsConstructor
public class ServiceDelivery {

    /** Timestamp when this response was generated */
    private String responseTimestamp;

    /** Identifier of the system producing this data (e.g., "NSB") */
    private String producerRef;

    /** Estimated timetable delivery containing journey predictions */
    private EstimatedTimetableDelivery estimatedTimetableDelivery;
}
