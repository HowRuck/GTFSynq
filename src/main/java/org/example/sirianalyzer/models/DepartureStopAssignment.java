package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents platform/quay assignment information for departure.
 * Contains details about where the vehicle will depart from at a specific stop.
 *
 * Mapped so that nested XML elements like:
 *
 * <DepartureStopAssignment>
 *   <PlatformRef>...</PlatformRef>
 *   <QuayRef>...</QuayRef>
 * </DepartureStopAssignment>
 */
@Getter
@Setter
@NoArgsConstructor
public class DepartureStopAssignment {

    /** Reference to the platform (e.g., "1", "2", "A") */
    private String platformRef;

    /** Reference to the quay (e.g., "NSR:Quay:519") */
    private String quayRef;

    /** Aimed quay reference for departure */
    private String aimedQuayRef;

    /** Expected quay reference for departure */
    private String expectedQuayRef;
}
