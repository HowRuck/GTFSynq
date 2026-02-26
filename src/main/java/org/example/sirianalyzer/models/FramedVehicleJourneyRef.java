package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Reference to a specific dated vehicle journey within a data frame.
 * Links the real-time data to the planned timetable.
 *
 * Mapped so that nested XML elements like:
 *
 * <FramedVehicleJourneyRef>
 *   <DataFrameRef>...</DataFrameRef>
 *   <DatedVehicleJourneyRef>...</DatedVehicleJourneyRef>
 * </FramedVehicleJourneyRef>
 *
 * are bound to the corresponding fields.
 */
@Getter
@Setter
@NoArgsConstructor
public class FramedVehicleJourneyRef {

    /** Operating date of the journey (e.g., "2019-08-16") */
    private String dataFrameRef;

    /** Unique identifier for the specific service journey (e.g., "NSB:ServiceJourney:1-2492-2343") */
    private String datedVehicleJourneyRef;
}
