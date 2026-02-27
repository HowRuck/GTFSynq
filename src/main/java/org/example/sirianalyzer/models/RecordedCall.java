package org.example.sirianalyzer.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonRootName;

/**
 * Represents a recorded call (completed stop) at a specific location.
 * Contains actual arrival/departure times for stops that have already been served.
 */

@JsonRootName("RecordedCall")
public record RecordedCall(
    /** Reference to the stop point/quay (e.g., "NSR:Quay:519") */
    String stopPointRef,

    /** Sequence number of this stop in the journey (1 = first stop) */
    Integer order,

    /** Human-readable name of the stop (e.g., "Oppdal") */
    String stopPointName,

    /** Planned/scheduled arrival time */
    String aimedArrivalTime,

    /** Actual arrival time at this stop */
    String actualArrivalTime,

    /** Arrival platform name (new - present in your XML) */
    String arrivalPlatformName,

    /** Indicates if this stop is cancelled */
    Boolean cancellation,

    /** Planned/scheduled departure time */
    String aimedDepartureTime,

    /** Departure platform name (new - present in your XML) */
    String departurePlatformName,

    /** Actual departure time from this stop */
    String actualDepartureTime,

    /** Indicates if the prediction for this stop is inaccurate */
    Boolean predictionInaccurate,

    /** Expected arrival time at this stop */
    String expectedArrivalTime,

    /** Expected departure time from this stop */
    String expectedDepartureTime,

    /** Visit number for this stop */
    Integer visitNumber,

    /** Occupancy information */
    String occupancy
) {}
