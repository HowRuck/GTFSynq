package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.junit.jupiter.api.Test;

class TripUpdateBatchMapperTest {

    private static final Instant EARLIER = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-24T12:00:10Z");

    private final TripUpdateBatchMapper mapper = new TripUpdateBatchMapper();

    /**
     * The deduplication service nulls the descriptor when only a stop changed. That trip used to be
     * missing from the parent rows, and the stop-time write failed its foreign key on every flush.
     */
    @Test
    void createsAParentRowForATripWhoseDescriptorWasDeduplicated() {
        var tripKey = 42L;

        var rows = mapper.map(List.of(new TripUpdateDto(null, List.of(stop(tripKey, 3, EARLIER)))));

        assertTrue(rows.descriptors().isEmpty());
        assertEquals(1, rows.hotStopTimes().size());
        assertEquals(List.of(tripKey), hotTripIds(rows));
    }

    @Test
    void mergesDescriptorAndStopContributionIntoSingleParentRow() {
        var tripKey = 42L;

        var rows = mapper.map(List.of(
                new TripUpdateDto(descriptor(tripKey, EARLIER), List.of(stop(tripKey, 3, LATER))),
                new TripUpdateDto(null, List.of(stop(tripKey, 3, EARLIER)))));

        assertEquals(1, rows.descriptors().size());
        assertEquals(1, rows.hotStopTimes().size(), "duplicate (trip, stop_sequence) rows must collapse");
        assertEquals(List.of(tripKey), hotTripIds(rows));
        assertEquals(LATER, rows.hotTripRows().getFirst().feedTs(), "the latest feed timestamp wins");
    }

    private static List<Long> hotTripIds(TripUpdateRows rows) {
        return rows.hotTripRows().stream()
                .map(TripUpdateRows.HotTripRow::tripUpdateId)
                .toList();
    }

    private static TripDescriptorDto descriptor(long id, Instant feedTs) {
        return new TripDescriptorDto(id, "entity-" + id, "feed", feedTs, "trip", "route", 0, null, null, (short) 0, 7L);
    }

    private static TripStopTimeUpdateDto stop(long tripKey, int stopSequence, Instant feedTs) {
        return new TripStopTimeUpdateDto(
                tripKey, "feed", feedTs, stopSequence, "stop", null, null, null, null, null, null, null, null, 9L);
    }
}
