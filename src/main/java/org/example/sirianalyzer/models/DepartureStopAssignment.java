package org.example.sirianalyzer.models;

/**
 * Represents platform/quay assignment information for departure.
 * Contains details about where the vehicle will depart from at a specific stop.
 */
public record DepartureStopAssignment(
    /** Reference to the platform (e.g., "1", "2", "A") */
    String platformRef,

    /** Reference to the quay (e.g., "NSR:Quay:519") */
    String quayRef,

    /** Aimed quay reference for departure */
    String aimedQuayRef,

    /** Expected quay reference for departure */
    String expectedQuayRef
) {}
