package org.example.sirianalyzer.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents the prediction quality/reliability level for estimated times.
 */
@Getter
@Setter
@NoArgsConstructor
public class PredictionQuality {

    /** The reliability level of the prediction (e.g., "veryReliable", "reliable", "unreliable") */
    private String predictionLevel;
}
