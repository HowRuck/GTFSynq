package org.example.sirianalyzer.models;

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
public record ArrivalStopAssignment(
    /** Reference to the platform (e.g., "1", "2", "A") */
    String platformRef,

    /** Reference to the quay (e.g., "NSR:Quay:519") */
    String quayRef,

    /** Originally planned/scheduled quay/platform reference */
    String aimedQuayRef,

    /** Currently expected/actual quay/platform reference (may differ from aimed if there's a change) */
    String expectedQuayRef,

    /** Actual quay reference for arrival (for recorded calls) */
    String actualQuayRef
) {}
