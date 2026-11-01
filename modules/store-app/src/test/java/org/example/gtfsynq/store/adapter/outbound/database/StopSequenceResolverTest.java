package org.example.gtfsynq.store.adapter.outbound.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void keepsPublishedSequencesWithoutLookingUpStaticStops() {
        var looked = new AtomicBoolean();
        var resolver = new StopSequenceResolver(keys -> {
            looked.set(true);
            return Map.of();
        });

        var update = update("trip", stop(4, "A"), stop(9, "B"));

        var resolved = resolver.resolve(List.of(update));

        assertEquals(List.of(4, 9), sequences(resolved));
        assertFalse(looked.get(), "a complete update must not cost a database lookup");
        assertSame(update, resolved.getFirst(), "a complete update is returned untouched");
    }

    @Test
    void resolvesMissingSequencesFromTheStaticSchedule() {
        var resolver = resolverReturning(
                stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2), new StaticStopTime("C", 3)));

        var resolved = resolver.resolve(List.of(update("trip", stop(null, "A"), stop(null, "B"), stop(null, "C"))));

        assertEquals(List.of(1, 2, 3), sequences(resolved));
    }

    @Test
    void resolvesASuffixOfTheTrip() {
        // Producers may drop stops the vehicle has already passed; matching must still line up.
        var resolver = resolverReturning(
                stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2), new StaticStopTime("C", 3)));

        var resolved = resolver.resolve(List.of(update("trip", stop(null, "B"), stop(null, "C"))));

        assertEquals(List.of(2, 3), sequences(resolved));
    }

    @Test
    void resolvesRepeatedStopsInVisitOrder() {
        // A loop route visits A twice: the cursor must pick the next visit, not always the first.
        var resolver = resolverReturning(
                stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2), new StaticStopTime("A", 3)));

        var resolved = resolver.resolve(List.of(update("trip", stop(null, "A"), stop(null, "B"), stop(null, "A"))));

        assertEquals(List.of(1, 2, 3), sequences(resolved));
    }

    @Test
    void fallsBackToMessageOrderWhenOneStopIsNotStatic() {
        // The static trip numbers stops 5 and 9; an unknown stop makes the whole message renumber,
        // because a mixture of real and ordinal sequences would not be a valid trip.
        var resolver = resolverReturning(stops(new StaticStopTime("A", 5), new StaticStopTime("B", 9)));

        var resolved = resolver.resolve(List.of(update("trip", stop(null, "A"), stop(null, "X"))));

        assertEquals(List.of(1, 2), sequences(resolved));
    }

    @Test
    void frequencyTripWithoutTripIdUsesMessageOrder() {
        var looked = new AtomicBoolean();
        var resolver = new StopSequenceResolver(keys -> {
            looked.set(true);
            return Map.of();
        });

        var resolved = resolver.resolve(
                List.of(new TripUpdateDto(descriptor(null), List.of(stop(null, "A"), stop(null, "B")))));

        assertEquals(List.of(1, 2), sequences(resolved));
        assertFalse(looked.get(), "a trip without a trip id has nothing to look up");
    }

    @Test
    void tripMissingFromTheStaticRevisionUsesMessageOrder() {
        var resolver = new StopSequenceResolver(keys -> Map.of());

        var resolved = resolver.resolve(List.of(update("trip", stop(null, "A"), stop(null, "B"))));

        assertEquals(List.of(1, 2), sequences(resolved));
    }

    @Test
    void sendsOnlyTripsWithMissingSequencesToTheLookup() {
        var seen = new java.util.HashSet<TripKey>();
        var resolver = new StopSequenceResolver(keys -> {
            seen.addAll(keys);
            return Map.of();
        });

        resolver.resolve(List.of(update("complete", stop(3, "A")), update("missing", stop(null, "B"))));

        assertEquals(Set.of(new TripKey("feed", "missing")), seen);
    }

    @Test
    void everyResolvedTripEndsWithDistinctSequences() {
        var resolver = resolverReturning(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2)));

        var resolved = resolver.resolve(List.of(
                update("trip", stop(null, "A"), stop(null, "B")), update("unknown", stop(null, "A"), stop(null, "A"))));

        assertTrue(resolved.stream()
                .allMatch(update -> update.stopTimeUpdates().stream()
                                .map(TripStopTimeUpdateDto::stopSequence)
                                .distinct()
                                .count()
                        == update.stopTimeUpdates().size()));
    }

    private static StopSequenceResolver resolverReturning(List<StaticStopTime> tripStops) {
        return new StopSequenceResolver(keys -> Map.of(TRIP, tripStops));
    }

    private static List<StaticStopTime> stops(StaticStopTime... stops) {
        return List.of(stops);
    }

    @Test
    void namesTheStopsOfASequenceOnlyFeed() {
        // PKP PLK's long-distance feed publishes sequences and no stop ids; the map needs the ids.
        var resolver = resolverReturning(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2)));

        var resolved = resolver.resolve(List.of(update("trip", stop(1, null), stop(2, null))));

        assertEquals(List.of(1, 2), sequences(resolved));
        assertEquals(List.of("A", "B"), stopIds(resolved));
    }

    @Test
    void looksUpATripThatOnlyNeedsStopIds() {
        var seen = new java.util.HashSet<TripKey>();
        var resolver = new StopSequenceResolver(keys -> {
            seen.addAll(keys);
            return Map.of(TRIP, stops(new StaticStopTime("A", 1)));
        });

        resolver.resolve(List.of(update("trip", stop(1, null))));

        assertEquals(Set.of(TRIP), seen, "a sequence-only update still needs the static schedule");
    }

    @Test
    void doesNotNameStopsFromSyntheticSequences() {
        // The static trip numbers its stops 1 and 2, but both rows arrive unidentified, so the
        // resolver falls back to message order. Looking those numbers up would name the wrong stops.
        var resolver = resolverReturning(stops(new StaticStopTime("A", 1), new StaticStopTime("B", 2)));

        var resolved = resolver.resolve(List.of(update("trip", stop(null, null), stop(null, null))));

        assertEquals(List.of(1, 2), sequences(resolved));
        assertEquals(Arrays.asList(null, null), stopIds(resolved));
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
