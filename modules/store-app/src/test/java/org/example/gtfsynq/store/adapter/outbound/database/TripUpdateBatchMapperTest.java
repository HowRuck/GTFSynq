package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;
import org.junit.jupiter.api.Test;

class TripUpdateBatchMapperTest {

    private static final Instant EARLIER = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-24T12:00:10Z");

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 11, 1);

    private final TripUpdateBatchMapper mapper =
            new TripUpdateBatchMapper(new StopSequenceResolver(), new EffectiveDelayResolver(), keys -> Map.of());

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

    @Test
    void fillsInMissingStopSequencesBeforeWritingRows() {
        var resolvedMapper = new TripUpdateBatchMapper(
                new StopSequenceResolver(), new EffectiveDelayResolver(), keys -> schedule("stop", 3));

        var rows = resolvedMapper.map(
                List.of(new TripUpdateDto(descriptor(42L, EARLIER), List.of(stop(42L, null, EARLIER)))));

        assertEquals(1, rows.hotStopTimes().size(), "an update without a sequence must still be written");
        assertEquals(3, rows.hotStopTimes().getFirst().stopSequence().intValue());
    }

    @Test
    void derivesTheEffectiveDelayOfATimeOnlyPrediction() {
        var scheduled = Instant.parse("2026-11-01T12:00:00Z");
        var effectiveMapper = new TripUpdateBatchMapper(
                new StopSequenceResolver(), new EffectiveDelayResolver(), keys -> schedule("stop", 3));

        var rows = effectiveMapper.map(List.of(new TripUpdateDto(
                descriptor(42L, EARLIER, SERVICE_DATE), List.of(stop(42L, 3, EARLIER, scheduled.plusSeconds(180))))));

        assertEquals(180, rows.hotStopTimes().getFirst().delaySeconds());
        assertEquals(
                scheduled.plusSeconds(180),
                rows.hotStopTimes().getFirst().effectiveArrivalTime(),
                "a published time is the effective time");
    }

    private static Map<TripKey, List<StaticStopTime>> schedule(String stopId, int sequence) {
        return Map.of(
                new TripKey("feed", "trip"),
                List.of(new StaticStopTime(
                        stopId, sequence, LocalTime.NOON, (short) 0, LocalTime.NOON, (short) 0, "UTC")));
    }

    private static List<Long> hotTripIds(TripUpdateRows rows) {
        return rows.hotTripRows().stream()
                .map(TripUpdateRows.HotTripRow::tripUpdateId)
                .toList();
    }

    private static TripDescriptorDto descriptor(long id, Instant feedTs) {
        return new TripDescriptorDto(id, "entity-" + id, "feed", feedTs, "trip", "route", 0, null, null, (short) 0, 7L);
    }

    private static TripDescriptorDto descriptor(long id, Instant feedTs, LocalDate startDate) {
        return new TripDescriptorDto(
                id, "entity-" + id, "feed", feedTs, "trip", "route", 0, startDate, null, (short) 0, 7L);
    }

    private static TripStopTimeUpdateDto stop(long tripKey, Integer stopSequence, Instant feedTs) {
        return new TripStopTimeUpdateDto(
                tripKey, "feed", feedTs, stopSequence, "stop", null, null, null, null, null, null, null, null, 9L);
    }

    private static TripStopTimeUpdateDto stop(long tripKey, Integer stopSequence, Instant feedTs, Instant arrivalTime) {
        return new TripStopTimeUpdateDto(
                tripKey,
                "feed",
                feedTs,
                stopSequence,
                "stop",
                arrivalTime,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                9L);
    }
}
