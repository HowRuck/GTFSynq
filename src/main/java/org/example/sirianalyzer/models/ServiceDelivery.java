package org.example.sirianalyzer.models;

/**
 * Service delivery container that wraps one or more specific delivery types.
 * Contains metadata about the response and the actual delivery payload.
 */
public record ServiceDelivery(
    /** Timestamp when this response was generated */
    String responseTimestamp,

    /** Identifier of the system producing this data (e.g., "NSB") */
    String producerRef,

    /** Estimated timetable delivery containing journey predictions */
    EstimatedTimetableDelivery estimatedTimetableDelivery
) {}
