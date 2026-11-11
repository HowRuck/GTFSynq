package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;
import org.junit.jupiter.api.Test;

class EffectiveDelayResolverTest {

    private static final String FEED = "feed";

    private static final Instant FEED_TS = Instant.parse("2026-11-01T12:00:00Z");

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 11, 1);

    private static final Instant SCHEDULED = Instant.parse("2026-11-01T12:00:00Z");

    private final EffectiveDelayResolver resolver = new EffectiveDelayResolver();

    @Test
    void keepsAPublishedDelayInsteadOfDerivingOne() {
        var predicted = SCHEDULED.plusSeconds(180);
        var row = row(1, predicted, 42, predicted, 7, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(42, resolved.delaySeconds(), "the published arrival delay wins");
        assertEquals(predicted, resolved.effectiveArrivalTime());
        assertEquals(predicted, resolved.effectiveDepartureTime());
    }

    @Test
    void derivesTheArrivalDelayOfATimeOnlyPrediction() {
        var predicted = SCHEDULED.plusSeconds(180);
        var row = row(1, predicted, null, null, null, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(180, resolved.delaySeconds());
        assertEquals(predicted, resolved.effectiveArrivalTime());
        assertEquals(
                predicted,
                resolved.effectiveDepartureTime(),
                "the stop's delay places the departure on the plan when no departure is predicted");
    }

    @Test
    void prefersAPublishedDepartureDelayOverADerivedArrivalDelay() {
        var predictedArrival = SCHEDULED.plusSeconds(180);
        var row = row(1, predictedArrival, null, null, 7, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(7, resolved.delaySeconds(), "a published value wins over a derived one");
    }

    @Test
    void derivesTheDepartureDelayWhenOnlyADepartureIsPublished() {
        var predicted = SCHEDULED.plusSeconds(90);
        var row = row(1, null, null, predicted, null, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(90, resolved.delaySeconds());
        assertEquals(predicted, resolved.effectiveDepartureTime());
    }

    @Test
    void placesTheArrivalFromADepartureOnlyDelay() {
        var row = row(1, null, null, null, 120, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(120, resolved.delaySeconds());
        assertEquals(SCHEDULED.plusSeconds(120), resolved.effectiveArrivalTime());
        assertEquals(SCHEDULED.plusSeconds(120), resolved.effectiveDepartureTime());
    }

    @Test
    void keepsEachEventsOwnDelayWhenBothArePublished() {
        var row = row(1, null, 30, null, 90, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(SCHEDULED.plusSeconds(30), resolved.effectiveArrivalTime());
        assertEquals(SCHEDULED.plusSeconds(90), resolved.effectiveDepartureTime());
    }

    @Test
    void impliesEachEventsOwnDelayFromItsOwnTime() {
        var row = row(
                1, SCHEDULED.plusSeconds(120), null, SCHEDULED.plusSeconds(200), null, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("UTC"));

        assertEquals(120, resolved.delaySeconds(), "the stop's delay is the arrival's");
        assertEquals(120, resolved.effectiveArrivalDelay());
        assertEquals(200, resolved.effectiveDepartureDelay(), "the departure keeps the delay its own time implies");
    }

    @Test
    void usesTheAgencyZoneAndTheDayOverflowOfAGtfsTime() {
        // 01:10 on the next service day in Berlin (CET, +01:00) is 00:10 UTC.
        var scheduled = Instant.parse("2026-11-02T00:10:00Z");
        var predicted = scheduled.plusSeconds(300);
        var row = row(1, predicted, null, null, null, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, schedule("Europe/Berlin", LocalTime.of(1, 10), (short) 1));

        assertEquals(300, resolved.delaySeconds());
        assertEquals(predicted, resolved.effectiveArrivalTime());
    }

    @Test
    void keepsAPublishedTimeWhenNoPlanIsKnown() {
        var predicted = SCHEDULED.plusSeconds(180);
        var row = row(1, predicted, null, null, null, ScheduleRelationship.SCHEDULED);

        var resolved = resolve(row, Map.of());

        assertNull(resolved.delaySeconds(), "without a plan there is nothing to compare against");
        assertEquals(predicted, resolved.effectiveArrivalTime(), "the feed's own time stays the effective time");
    }

    @Test
    void doesNotInventATimeForASkippedStop() {
        var row = row(1, null, null, null, null, ScheduleRelationship.SKIPPED);

        var resolved = resolve(row, schedule("UTC"));

        assertNull(resolved.delaySeconds());
        assertNull(resolved.effectiveArrivalTime());
        assertNull(resolved.effectiveDepartureTime());
    }

    @Test
    void returnsTheSameUpdateWhenNothingCanBeDerived() {
        var update = update(row(1, null, null, null, null, ScheduleRelationship.SCHEDULED));

        assertSame(update, resolver.resolve(List.of(update), Map.of()).getFirst());
    }

    @Test
    void onlyLooksUpTripsThatAreMissingATimeOrDelay() {
        var complete = update(row(1, SCHEDULED, 0, SCHEDULED, 0, ScheduleRelationship.SCHEDULED));
        var timeOnly = update(row(1, SCHEDULED, null, null, null, ScheduleRelationship.SCHEDULED));
        var skipped = update(row(1, null, null, null, null, ScheduleRelationship.SKIPPED));
        var withoutServiceDate = new TripUpdateDto(
                descriptor(null), List.of(row(1, SCHEDULED, null, null, null, ScheduleRelationship.SCHEDULED)));

        assertEquals(Set.of(), EffectiveDelayResolver.keysNeedingLookup(List.of(complete)));
        assertEquals(Set.of(new TripKey(FEED, "trip")), EffectiveDelayResolver.keysNeedingLookup(List.of(timeOnly)));
        assertEquals(Set.of(), EffectiveDelayResolver.keysNeedingLookup(List.of(skipped)));
        assertEquals(Set.of(), EffectiveDelayResolver.keysNeedingLookup(List.of(withoutServiceDate)));
        assertTrue(EffectiveDelayResolver.keysNeedingLookup(List.of(timeOnly)).contains(new TripKey(FEED, "trip")));
    }

    private TripStopTimeUpdateDto resolve(TripStopTimeUpdateDto row, Map<TripKey, List<StaticStopTime>> schedule) {
        return resolver.resolve(List.of(update(row)), schedule)
                .getFirst()
                .stopTimeUpdates()
                .getFirst();
    }

    private static Map<TripKey, List<StaticStopTime>> schedule(String timezone) {
        return schedule(timezone, LocalTime.NOON, (short) 0);
    }

    private static Map<TripKey, List<StaticStopTime>> schedule(String timezone, LocalTime arrival, Short overflowDays) {
        return Map.of(
                new TripKey(FEED, "trip"),
                List.of(new StaticStopTime("stop", 1, arrival, overflowDays, arrival, overflowDays, timezone)));
    }

    private static TripUpdateDto update(TripStopTimeUpdateDto row) {
        return new TripUpdateDto(descriptor(SERVICE_DATE), List.of(row));
    }

    private static TripDescriptorDto descriptor(LocalDate startDate) {
        return new TripDescriptorDto(
                1L, "entity", FEED, FEED_TS, "trip", "route", null, startDate, null, (short) 0, 2L);
    }

    private static TripStopTimeUpdateDto row(
            Integer stopSequence,
            Instant arrival,
            Integer arrivalDelay,
            Instant departure,
            Integer departureDelay,
            ScheduleRelationship relationship) {
        return new TripStopTimeUpdateDto(
                1L,
                FEED,
                FEED_TS,
                stopSequence,
                "stop",
                arrival,
                arrivalDelay,
                null,
                departure,
                departureDelay,
                null,
                relationship,
                null,
                9L);
    }
}
