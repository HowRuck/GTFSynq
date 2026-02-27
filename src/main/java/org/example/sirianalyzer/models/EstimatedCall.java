package org.example.sirianalyzer.models;

import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Represents an estimated call (stop) at a specific location.
 * Contains both planned (aimed) and real-time estimated arrival/departure information.
 *
 * Field-to-element mapping is annotated explicitly to match SIRI element names.
 */
public record EstimatedCall(
    /** Reference to the stop point/quay (e.g., "NSR:Quay:519") */
    String stopPointRef,

    /** Reference to identify a specific visit to a stop point */
    String visitNumber,

    /** Sequence number of this stop in the journey (1 = first stop) */
    Integer order,

    /** Human-readable name of the stop (e.g., "Oppdal") */
    String stopPointName,

    /** Destination display text shown to passengers */
    String destinationDisplay,

    /** Indicates if this is a request stop (true) or a regular stop (false) */
    Boolean requestStop,

    /** Indicates if this stop is cancelled */
    Boolean cancellation,

    /** Planned/scheduled arrival time */
    String aimedArrivalTime,

    /** Aimed platform name for arrival */
    String aimedArrivalPlatformName,

    /** Real-time estimated arrival time */
    String expectedArrivalTime,

    /** Expected platform name for arrival */
    String expectedArrivalPlatformName,

    /** Status of arrival: "onTime", "early", "delayed", "cancelled", etc. */
    String arrivalStatus,

    /** Boarding activity at arrival: "alighting", "boarding", "noAlighting", "passThru" */
    String arrivalBoardingActivity,

    /** Platform/track name for arrival (e.g., "1", "2", "A") */
    String arrivalPlatformName,

    /** Information about platform/quay assignment for arrival */
    ArrivalStopAssignment arrivalStopAssignment,

    /** Planned/scheduled departure time */
    String aimedDepartureTime,

    /** Aimed platform name for departure */
    String aimedDeparturePlatformName,

    /** Real-time estimated departure time */
    String expectedDepartureTime,

    /** Expected platform name for departure */
    String expectedDeparturePlatformName,

    /** Status of departure: "onTime", "early", "delayed", "cancelled", etc. */
    String departureStatus,

    /** Actual arrival time (for recorded calls) */
    String actualArrivalTime,

    /** Actual departure time (for recorded calls) */
    String actualDepartureTime,

    /** Whether the prediction is marked as inaccurate (SIRI element: PredictionInaccurate) */
    Boolean predictionInaccurate,

    /** Prediction quality information for arrival */
    PredictionQuality expectedArrivalPredictionQuality,

    /** Prediction quality information for departure */
    PredictionQuality expectedDeparturePredictionQuality,

    /** Boarding activity at departure: "boarding", "noBoarding", "passThru", used when there is a change in the boarding restrictions */
    String departureBoardingActivity, // "boarding", "noBoarding", "passThru"

    /** Reference to situations providing supplementary information */
    String situationRef,

    /** Indicates if this is a timing point (true) or not (false) */
    Boolean timingPoint,

    /** Platform/track name for departure (e.g., "1", "2", "A") */
    String departurePlatformName,

    /** Information about platform/quay assignment for departure */
    DepartureStopAssignment departureStopAssignment,

    /** Call note providing additional information about the call */
    String callNote,

    /** Extensions containing additional vendor-specific or domain-specific information */
    Extensions extensions
) {}
