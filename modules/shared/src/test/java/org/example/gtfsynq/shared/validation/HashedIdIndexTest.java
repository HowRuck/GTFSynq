package org.example.gtfsynq.shared.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class HashedIdIndexTest {

    @Test
    void tracksMembershipAcrossGrowth() {
        var index = new HashedIdIndex(100_000);
        var expected = new HashSet<String>();
        for (int i = 0; i < 5_000; i++) {
            var id = "stop-" + i;
            assertEquals(HashedIdIndex.IdSlot.ADDED, index.add(id));
            expected.add(id);
        }
        assertEquals(expected.size(), index.size());
        assertFalse(index.truncated());
        for (var id : expected) {
            assertTrue(index.contains(id), id);
        }
        assertFalse(index.contains("stop-5000"));
        assertFalse(index.contains(""));
    }

    @Test
    void reportsRepeatedIdsAsPresent() {
        var index = new HashedIdIndex(1_000);
        assertEquals(HashedIdIndex.IdSlot.ADDED, index.add("trip-1"));
        assertEquals(HashedIdIndex.IdSlot.PRESENT, index.add("trip-1"));
        assertEquals(1, index.size());
    }

    @Test
    void stopsTrackingAtTheConfiguredLimit() {
        var index = new HashedIdIndex(16);
        for (int i = 0; i < 16; i++) {
            assertEquals(HashedIdIndex.IdSlot.ADDED, index.add("id-" + i));
        }
        assertFalse(index.truncated());
        assertEquals(HashedIdIndex.IdSlot.UNTRACKED, index.add("id-16"));
        assertTrue(index.truncated());
        // Ids tracked before the limit stay queryable, and repeats are still recognised.
        assertTrue(index.contains("id-0"));
        assertEquals(HashedIdIndex.IdSlot.PRESENT, index.add("id-0"));
    }

    @Test
    void approximatesAHashSetOverManyIds() {
        // Exercises collisions and growth at a scale where a broken probe would show up.
        var index = new HashedIdIndex(300_000);
        var expected = new HashSet<String>();
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < 200_000; i++) {
            var id = Long.toString(random.nextLong(), 36) + "-" + i;
            expected.add(id);
            index.add(id);
        }
        assertEquals(expected.size(), index.size());
        for (var id : expected) {
            assertTrue(index.contains(id), id);
        }
        for (int i = 0; i < 1_000; i++) {
            assertFalse(index.contains("absent-" + i));
        }
    }
}
