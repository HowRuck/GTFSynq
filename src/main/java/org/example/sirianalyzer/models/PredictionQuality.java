package org.example.sirianalyzer.models;

/**
 * Represents the prediction quality/reliability level for estimated times.
 */
public record PredictionQuality(
    /** The reliability level of the prediction (e.g., "veryReliable", "reliable", "unreliable") */
    String predictionLevel
) {}
