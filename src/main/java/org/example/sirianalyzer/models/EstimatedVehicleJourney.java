package org.example.sirianalyzer.models;

/**
 * Represents a single vehicle journey with real-time estimated arrival/departure information.
 * Contains route information and all estimated calls (stops) along the journey.
 */
public record EstimatedVehicleJourney(
    /** Timestamp when this vehicle journey information was recorded */
    String recordedAtTime,

    /** Reference to the line/route (e.g., "NSB:Line:21") */
    String lineRef,

    /** Direction of travel on the line (e.g., "1" for outbound, "2" for inbound, or "Outbound") */
    String directionRef,

    /** Reference to the specific dated vehicle journey in the timetable */
    FramedVehicleJourneyRef framedVehicleJourneyRef,

    /** Reference to the origin stop point */
    String originRef,

    /** Name of the journey's origin stop */
    String originName,

    /** Reference to the destination stop point */
    String destinationRef,

    /** Name of the journey's final destination stop */
    String destinationName,

    /** Reference to the operating company (e.g., "NSB") */
    String operatorRef,

    /** Product category reference (e.g., "Flytoget", "Lt", "Rt") */
    String productCategoryRef,

    /** Mode of transport (e.g., "rail", "bus", "tram") */
    String vehicleMode,

    /** Reference to Route in question */
    String routeRef,

    /** Reference to Network/GroupOfLines in question */
    String groupOfLinesRef,

    /** Reference to Line in question that the departure replaces */
    String externalLineRef,

    /** Indicates if the vehicle is being monitored */
    Boolean monitored,

    /** Data source identifier */
    String dataSource,

    /** Service feature reference (e.g., "passengerTrain", "Unknown") */
    String serviceFeatureRef,

    /** Block reference for the vehicle journey */
    String blockRef,

    /** Vehicle identifier/reference */
    String vehicleRef,

    /** Collection of recorded calls (completed stops) for this journey */
    RecordedCalls recordedCalls,

    /** Collection of estimated calls (upcoming stops) for this journey */
    EstimatedCalls estimatedCalls,

    /** Indicates if all stops in the sequence are included (true) or if some are omitted (false) */
    Boolean isCompleteStopSequence,

    /** Indicates if the entire vehicle journey is cancelled */
    Boolean cancellation,

    /** Unique code for the estimated vehicle journey */
    String estimatedVehicleJourneyCode,

    /** Reference to the specific dated vehicle journey */
    String datedVehicleJourneyRef,

    /** Indicates if this is an extra journey (true) or a regular scheduled journey (false) */
    Boolean extraJourney,

    /** Reference to the journey pattern for this vehicle journey */
    String journeyPatternRef,

    /** Name of the journey pattern */
    String journeyPatternName,

    /** Occupancy status of the vehicle (e.g., "empty", "manySeatsAvailable", "fewSeatsAvailable", "standingRoomOnly", "crushedStandingRoomOnly") */
    String occupancy,

    /**
     * Public number or name of the line
     *
     * Is only used when replacement departures reference a new LineRef that is not already defined in timetable data.
     * It will then be used to represent the added Line.
     */
    String publishedLineName,

    /** Name of the direction */
    String directionName,

    /** Display of the destination at the origin */
    String destinationDisplayAtOrigin,

    /** Via point information (intermediate stops or routing) */
    Via via,

    /** Aimed departure time from the origin stop */
    String originAimedDepartureTime,

    /** Aimed arrival time at the destination stop */
    String destinationAimedArrivalTime,

    /** Additional notes about the journey (e.g., route information, special instructions) */
    JourneyNote journeyNote,

    /** Extensions containing additional vendor-specific or domain-specific information */
    Extensions extensions,

    /** Contact point for the public */
    String publicContact,

    /** Administrative contact details */
    String operationsContact,

    /** Reference to situations providing supplementary information */
    String situationRef,

    /** Reference to the VehicleJourney being replaced */
    String vehicleJourneyRef,

    /** Indicates if the vehicle is in congestion */
    Boolean inCongestion
) {}
