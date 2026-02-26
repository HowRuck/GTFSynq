package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents an estimated call (stop) at a specific location.
 * Contains both planned (aimed) and real-time estimated arrival/departure information.
 *
 * Field-to-element mapping is annotated explicitly to match SIRI element names.
 */
@Getter
@Setter
@NoArgsConstructor
public class EstimatedCall {

    /** Reference to the stop point/quay (e.g., "NSR:Quay:519") */
    private String stopPointRef;

    /** Reference to identify a specific visit to a stop point */
    private String visitNumber;

    /** Sequence number of this stop in the journey (1 = first stop) */
    private Integer order;

    /** Human-readable name of the stop (e.g., "Oppdal") */
    private String stopPointName;

    /** Destination display text shown to passengers */
    private String destinationDisplay;

    /** Indicates if this is a request stop (true) or a regular stop (false) */
    private Boolean requestStop;

    /** Indicates if this stop is cancelled */
    private Boolean cancellation;

    /** Planned/scheduled arrival time */
    private String aimedArrivalTime;

    /** Aimed platform name for arrival */
    private String aimedArrivalPlatformName;

    /** Real-time estimated arrival time */
    private String expectedArrivalTime;

    /** Expected platform name for arrival */
    private String expectedArrivalPlatformName;

    /** Status of arrival: "onTime", "early", "delayed", "cancelled", etc. */
    private String arrivalStatus;

    /** Boarding activity at arrival: "alighting", "boarding", "noAlighting", "passThru" */
    private String arrivalBoardingActivity;

    /** Platform/track name for arrival (e.g., "1", "2", "A") */
    private String arrivalPlatformName;

    /** Information about platform/quay assignment for arrival */
    private ArrivalStopAssignment arrivalStopAssignment;

    /** Planned/scheduled departure time */
    private String aimedDepartureTime;

    /** Aimed platform name for departure */
    private String aimedDeparturePlatformName;

    /** Real-time estimated departure time */
    private String expectedDepartureTime;

    /** Expected platform name for departure */
    private String expectedDeparturePlatformName;

    /** Status of departure: "onTime", "early", "delayed", "cancelled", etc. */
    private String departureStatus;

    /** Actual arrival time (for recorded calls) */
    private String actualArrivalTime;

    /** Actual departure time (for recorded calls) */
    private String actualDepartureTime;

    /** Whether the prediction is marked as inaccurate (SIRI element: PredictionInaccurate) */
    private Boolean predictionInaccurate;

    /** Prediction quality information for arrival */
    private PredictionQuality expectedArrivalPredictionQuality;

    /** Prediction quality information for departure */
    private PredictionQuality expectedDeparturePredictionQuality;

    /** Boarding activity at departure: "boarding", "noBoarding", "passThru", used when there is a change in the boarding restrictions */
    private String departureBoardingActivity; // "boarding", "noBoarding", "passThru"

    /** Reference to situations providing supplementary information */
    private String situationRef;

    /** Indicates if this is a timing point (true) or not (false) */
    private Boolean timingPoint;

    /** Platform/track name for departure (e.g., "1", "2", "A") */
    private String departurePlatformName;

    /** Information about platform/quay assignment for departure */
    private DepartureStopAssignment departureStopAssignment;

    /** Call note providing additional information about the call */
    private String callNote;

    /** Extensions containing additional vendor-specific or domain-specific information */
    private Extensions extensions;
}
