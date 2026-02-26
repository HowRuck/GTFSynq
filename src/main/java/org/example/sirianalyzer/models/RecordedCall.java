package org.example.sirianalyzer.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a recorded call (completed stop) at a specific location.
 * Contains actual arrival/departure times for stops that have already been served.
 */

@Getter
@Setter
@NoArgsConstructor
@JsonRootName("RecordedCall")
public class RecordedCall {

    /** Delegating creator: allow deserializing a plain string into a RecordedCall (stopPointRef) */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public RecordedCall(String stopPointRef) {
        this.stopPointRef = stopPointRef;
    }

    /** Reference to the stop point/quay (e.g., "NSR:Quay:519") */
    private String stopPointRef;

    /** Sequence number of this stop in the journey (1 = first stop) */
    private Integer order;

    /** Human-readable name of the stop (e.g., "Oppdal") */
    private String stopPointName;

    /** Planned/scheduled arrival time */
    private String aimedArrivalTime;

    /** Actual arrival time at this stop */
    private String actualArrivalTime;

    /** Arrival platform name (new - present in your XML) */
    private String arrivalPlatformName;

    /** Indicates if this stop is cancelled */
    private Boolean cancellation;

    /** Planned/scheduled departure time */
    private String aimedDepartureTime;

    /** Departure platform name (new - present in your XML) */
    private String departurePlatformName;

    /** Actual departure time from this stop */
    private String actualDepartureTime;

    /** Indicates if the prediction for this stop is inaccurate */
    private Boolean predictionInaccurate;

    /** Expected arrival time at this stop */
    private String expectedArrivalTime;

    /** Expected departure time from this stop */
    private String expectedDepartureTime;

    /** Visit number for this stop */
    private Integer visitNumber;

    /** Occupancy information */
    private String occupancy;
}
