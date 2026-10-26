package org.example.gtfsynq.api.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class GtfsQueryRepositoryTest {

    private static final OffsetDateTime EARLIER = OffsetDateTime.parse("2026-10-26T10:00:00Z");
    private static final OffsetDateTime LATER = OffsetDateTime.parse("2026-10-26T10:05:00Z");

    @Test
    void prefersTheLaterActualStopTime() {
        assertTrue(GtfsQueryRepository.isLater(LATER, 3, EARLIER, 9), "the actual time wins over the sequence");
        assertFalse(GtfsQueryRepository.isLater(EARLIER, 9, LATER, 3));
    }

    @Test
    void fallsBackToTheStopSequenceForEqualTimes() {
        assertTrue(GtfsQueryRepository.isLater(EARLIER, 9, EARLIER, 3));
        assertFalse(GtfsQueryRepository.isLater(EARLIER, 3, EARLIER, 9));
    }

    @Test
    void prefersAKnownTimeOverAnUnknownOne() {
        assertTrue(GtfsQueryRepository.isLater(LATER, 3, null, 9));
        assertFalse(GtfsQueryRepository.isLater(null, 9, LATER, 3));
    }

    @Test
    void fallsBackToTheStopSequenceWhenBothTimesAreUnknown() {
        assertTrue(GtfsQueryRepository.isLater(null, 9, null, 3));
        assertFalse(GtfsQueryRepository.isLater(null, 3, null, 9));
    }
}
