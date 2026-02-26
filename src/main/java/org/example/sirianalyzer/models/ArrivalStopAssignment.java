package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Information about platform/quay assignment for an arrival.
 * Used to communicate platform changes from the planned assignment.
 *
 * XML element mapping (SIRI):
 * - PlatformRef       -> platformRef
 * - QuayRef           -> quayRef
 * - AimedQuayRef      -> aimedQuayRef
 * - ExpectedQuayRef   -> expectedQuayRef
 * - ActualQuayRef     -> actualQuayRef
 */
@Getter
@Setter
@NoArgsConstructor
public class ArrivalStopAssignment {

    /** Reference to the platform (e.g., "1", "2", "A") */
    private String platformRef;

    /** Reference to the quay (e.g., "NSR:Quay:519") */
    private String quayRef;

    /** Originally planned/scheduled quay/platform reference */
    private String aimedQuayRef;

    /** Currently expected/actual quay/platform reference (may differ from aimed if there's a change) */
    private String expectedQuayRef;

    /** Actual quay reference for arrival (for recorded calls) */
    private String actualQuayRef;
}
