package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;
import org.junit.jupiter.api.Test;

class StopSequenceResolverTest {

    private static final Instant FEED_TS = Instant.parse("2026-11-01T12:00:00Z");

    private static final TripKey TRIP = new TripKey("feed", "trip");

    private static final StopSequenceResolver RESOLVER = new StopSequenceResolver();

    @Test
    void keepsPublishedSequencesWithoutLookingUpStaticStops() {
        var update = update("trip", stop(4, "A"), stop(9, "B"));

        var resolved = RESOLVER.resolve(List.of(update), Map.of());

        assertEquals(List.of(4, 9), sequences(resolved));
        assertSame(update, resolved.getFirst(), "a complete update is returned untouched");
    }

    @Test
    void resolvesMissingSequencesFromTheStaticSchedule() {
        var resolved = RESOLVER.resolve(
                List.of(update("trip", stop(null, "A"), stop(null, "B"), stop(null, "C"))),
                schedule(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2), new StaticStopTime("C", 3))));

        assertEquals(List.of(1, 2, 3), sequences(resolved));
    }

    @Test
    void resolvesASuffixOfTheTrip() {
        // Producers may drop stops the vehicle has already passed; matching must still line up.
        var resolved = RESOLVER.resolve(
                List.of(update("trip", stop(null, "B"), stop(null, "C"))),
                schedule(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2), new StaticStopTime("C", 3))));

        assertEquals(List.of(2, 3), sequences(resolved));
    }

    @Test
    void resolvesRepeatedStopsInVisitOrder() {
        // A loop route visits A twice: the cursor must pick the next visit, not always the first.
        var resolved = RESOLVER.resolve(
                List.of(update("trip", stop(null, "A"), stop(null, "B"), stop(null, "A"))),
                schedule(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2), new StaticStopTime("A", 3))));

        assertEquals(List.of(1, 2, 3), sequences(resolved));
    }

    @Test
    void fallsBackToMessageOrderWhenOneStopIsNotStatic() {
        // The static trip numbers stops 5 and 9; an unknown stop makes the whole message renumber,
        // because a mixture of real and ordinal sequences would not be a valid trip.
        var resolved = RESOLVER.resolve(
                List.of(update("trip", stop(null, "A"), stop(null, "X"))),
                schedule(stops(new StaticStopTime("A", 5), new StaticStopTime("B", 9))));

        assertEquals(List.of(1, 2), sequences(resolved));
    }

    @Test
    void frequencyTripWithoutTripIdUsesMessageOrder() {
        var resolved = RESOLVER.resolve(
                List.of(new TripUpdateDto(descriptor(null), List.of(stop(null, "A"), stop(null, "B")))), Map.of());

        assertEquals(List.of(1, 2), sequences(resolved));
    }

    @Test
    void tripMissingFromTheStaticRevisionUsesMessageOrder() {
        var resolved = RESOLVER.resolve(List.of(update("trip", stop(null, "A"), stop(null, "B"))), Map.of());

        assertEquals(List.of(1, 2), sequences(resolved));
    }

    @Test
    void sendsOnlyTripsWithMissingSequencesToTheLookup() {
        var keys = StopSequenceResolver.keysNeedingLookup(
                List.of(update("complete", stop(3, "A")), update("missing", stop(null, "B"))));

        assertEquals(Set.of(new TripKey("feed", "missing")), keys);
    }

    @Test
    void everyResolvedTripEndsWithDistinctSequences() {
        var resolved = RESOLVER.resolve(
                List.of(
                        update("trip", stop(null, "A"), stop(null, "B")),
                        update("unknown", stop(null, "A"), stop(null, "A"))),
                schedule(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2))));

        assertTrue(resolved.stream()
                .allMatch(update -> update.stopTimeUpdates().stream()
                                .map(TripStopTimeUpdateDto::stopSequence)
                                .distinct()
                                .count()
                        == update.stopTimeUpdates().size()));
    }

    @Test
    void namesTheStopsOfASequenceOnlyFeed() {
        // PKP PLK's long-distance feed publishes sequences and no stop ids; the map needs the ids.
        var resolved = RESOLVER.resolve(
                List.of(update("trip", stop(1, null), stop(2, null))),
                schedule(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2))));

        assertEquals(List.of(1, 2), sequences(resolved));
        assertEquals(List.of("A", "B"), stopIds(resolved));
    }

    @Test
    void looksUpATripThatOnlyNeedsStopIds() {
        var keys = StopSequenceResolver.keysNeedingLookup(List.of(update("trip", stop(1, null))));

        assertEquals(Set.of(TRIP), keys, "a sequence-only update still needs the static schedule");
    }

    @Test
    void doesNotNameStopsFromSyntheticSequences() {
        // The static trip numbers its stops 1 and 2, but both rows arrive unidentified, so the
        // resolver falls back to message order. Looking those numbers up would name the wrong stops.
        var resolved = RESOLVER.resolve(
                List.of(update("trip", stop(null, null), stop(null, null))),
                schedule(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2))));

        assertEquals(List.of(1, 2), sequences(resolved));
        assertEquals(Arrays.asList(null, null), stopIds(resolved));
    }

    private static Map<TripKey, List<StaticStopTime>> schedule(List<StaticStopTime> stops) {
        return Map.of(TRIP, stops);
    }

    private static List<StaticStopTime> stops(StaticStopTime... stops) {
        return List.of(stops);
    }

    private static List<String> stopIds(List<TripUpdateDto> updates) {
        return updates.stream()
                .flatMap(update -> update.stopTimeUpdates().stream())
                .map(TripStopTimeUpdateDto::stopId)
                .collect(Collectors.toList());
    }

    private static List<Integer> sequences(List<TripUpdateDto> updates) {
        return updates.stream()
                .flatMap(update -> update.stopTimeUpdates().stream())
                .map(TripStopTimeUpdateDto::stopSequence)
                .collect(Collectors.toList());
    }

    private static TripUpdateDto update(String tripId, TripStopTimeUpdateDto... rows) {
        return new TripUpdateDto(descriptor(tripId), List.of(rows));
    }

    private static TripDescriptorDto descriptor(String tripId) {
        return new TripDescriptorDto(1L, "entity", "feed", FEED_TS, tripId, null, null, null, null, (short) 0, 2L);
    }

    private static TripStopTimeUpdateDto stop(Integer stopSequence, String stopId) {
        return new TripStopTimeUpdateDto(
                1L, "feed", FEED_TS, stopSequence, stopId, null, null, null, null, null, null, null, null, 9L);
    }
}
