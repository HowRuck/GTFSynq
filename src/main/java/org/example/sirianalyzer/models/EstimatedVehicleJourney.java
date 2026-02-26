package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a single vehicle journey with real-time estimated arrival/departure information.
 * Contains route information and all estimated calls (stops) along the journey.
 */
@Getter
@Setter
@NoArgsConstructor
public class EstimatedVehicleJourney {

    /** Timestamp when this vehicle journey information was recorded */
    private String recordedAtTime;

    /** Reference to the line/route (e.g., "NSB:Line:21") */
    private String lineRef;

    /** Direction of travel on the line (e.g., "1" for outbound, "2" for inbound, or "Outbound") */
    private String directionRef;

    /** Reference to the specific dated vehicle journey in the timetable */
    private FramedVehicleJourneyRef framedVehicleJourneyRef;

    /** Reference to the origin stop point */
    private String originRef;

    /** Name of the journey's origin stop */
    private String originName;

    /** Reference to the destination stop point */
    private String destinationRef;

    /** Name of the journey's final destination stop */
    private String destinationName;

    /** Reference to the operating company (e.g., "NSB") */
    private String operatorRef;

    /** Product category reference (e.g., "Flytoget", "Lt", "Rt") */
    private String productCategoryRef;

    /** Mode of transport (e.g., "rail", "bus", "tram") */
    private String vehicleMode;

    /** Reference to Route in question */
    private String routeRef;

    /** Reference to Network/GroupOfLines in question */
    private String groupOfLinesRef;

    /** Reference to Line in question that the departure replaces */
    private String externalLineRef;

    /** Indicates if the vehicle is being monitored */
    private Boolean monitored;

    /** Data source identifier */
    private String dataSource;

    /** Service feature reference (e.g., "passengerTrain", "Unknown") */
    private String serviceFeatureRef;

    /** Block reference for the vehicle journey */
    private String blockRef;

    /** Vehicle identifier/reference */
    private String vehicleRef;

    /** Collection of recorded calls (completed stops) for this journey */
    private RecordedCalls recordedCalls;

    /** Collection of estimated calls (upcoming stops) for this journey */
    private EstimatedCalls estimatedCalls;

    /** Indicates if all stops in the sequence are included (true) or if some are omitted (false) */
    private Boolean isCompleteStopSequence;

    /** Indicates if the entire vehicle journey is cancelled */
    private Boolean cancellation;

    /** Unique code for the estimated vehicle journey */
    private String estimatedVehicleJourneyCode;

    /** Reference to the specific dated vehicle journey */
    private String datedVehicleJourneyRef;

    /** Indicates if this is an extra journey (true) or a regular scheduled journey (false) */
    private Boolean extraJourney;

    /** Reference to the journey pattern for this vehicle journey */
    private String journeyPatternRef;

    /** Name of the journey pattern */
    private String journeyPatternName;

    /** Occupancy status of the vehicle (e.g., "empty", "manySeatsAvailable", "fewSeatsAvailable", "standingRoomOnly", "crushedStandingRoomOnly") */
    private String occupancy;

    /**
     * Public number or name of the line
     *
     * Is only used when replacement departures reference a new LineRef that is not already defined in timetable data.
     * It will then be used to represent the added Line.
     */
    private String publishedLineName;

    /** Name of the direction */
    private String directionName;

    /** Display of the destination at the origin */
    private String destinationDisplayAtOrigin;

    /** Via point information (intermediate stops or routing) */
    private Via via;

    /** Aimed departure time from the origin stop */
    private String originAimedDepartureTime;

    /** Aimed arrival time at the destination stop */
    private String destinationAimedArrivalTime;

    /** Additional notes about the journey (e.g., route information, special instructions) */
    private JourneyNote journeyNote;

    /** Extensions containing additional vendor-specific or domain-specific information */
    private Extensions extensions;

    /** Contact point for the public */
    private String publicContact;

    /** Administrative contact details */
    private String operationsContact;

    /** Reference to situations providing supplementary information */
    private String situationRef;

    /** Reference to the VehicleJourney being replaced */
    private String vehicleJourneyRef;

    /** Indicates if the vehicle is in congestion */
    private Boolean inCongestion;
}
