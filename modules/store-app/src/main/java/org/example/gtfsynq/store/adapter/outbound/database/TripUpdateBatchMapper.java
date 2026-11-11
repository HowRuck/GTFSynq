package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTime;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StaticStopTimes;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.StopSelection;
import org.example.gtfsynq.store.adapter.outbound.database.StopSequenceResolver.TripKey;
import org.example.gtfsynq.store.adapter.outbound.database.TripUpdateRows.HotTripRow;

/**
 * Maps a drained sink batch into the per-table row streams that
 * {@link TripUpdateRepository} writes.
 *
 * <p>This is where the batch's shape and its "latest wins" policy live: rows are flattened out of
 * the {@link TripUpdateDto}s and then collapsed by the conflict key of each {@code ON CONFLICT DO
 * UPDATE} target. Keeping the policy here, rather than inside the repository, keeps that class a
 * plain batch executor and makes the rule an explicit, named decision. The resulting list sizes are
 * the counts the sink reports as written rows.
 *
 * <p>Stop sequences are completed first: the hot table is keyed by {@code (trip, stop_sequence)},
 * and a feed that publishes only {@code stop_id} would otherwise contribute no stop-time rows at
 * all. {@link StopSequenceResolver} fills them from the static schedule, or from message order when
 * the trip cannot be matched.
 *
 * <p>The same static read carries the scheduled times and the agency time zone, so
 * {@link EffectiveDelayResolver} can fill the effective delay and event times in the same pass. A
 * row that already carries both times and both delays needs no lookup; its published values are
 * copied into the effective columns.
 *
 * <p>Collapsing is not optional: {@code reWriteBatchedInserts} merges a batch into a single
 * multi-row INSERT, and PostgreSQL rejects such a statement if it targets the same conflict key
 * twice. The history table uses {@code DO NOTHING} and tolerates duplicates, so it is left
 * untouched.
 *
 * <p>Each input list is traversed once: the descriptor and stop-time batches are each collapsed in
 * a single pass that also feeds the shared current-state trip accumulator.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class TripUpdateBatchMapper {

    private final StopSequenceResolver stopSequences;

    private final EffectiveDelayResolver effectiveDelays;

    private final StaticStopTimes staticStopTimes;

    /**
     * Shapes a batch into the row streams for each target table.
     *
     * @param batch a drained batch of already-deduplicated trip updates
     * @return the row streams to persist, never {@code null}
     */
    public TripUpdateRows map(List<TripUpdateDto> batch) {
        var schedules = schedulesFor(batch);
        var sequenced = stopSequences.resolve(batch, schedules);
        var effective = effectiveDelays.resolve(sequenced, schedules);
        var descriptors = new ArrayList<TripDescriptorDto>(effective.size());
        var stopTimes = new ArrayList<TripStopTimeUpdateDto>(stopTimeRowCount(effective));

        for (var dto : effective) {
            if (dto.tripDescriptor() != null) {
                descriptors.add(dto.tripDescriptor());
            }
            var rows = dto.stopTimeUpdates();
            if (rows != null) {
                for (var row : rows) {
                    if (row.stopSequence() != null) {
                        stopTimes.add(row);
                    }
                }
            }
        }

        if (descriptors.isEmpty() && stopTimes.isEmpty()) {
            return new TripUpdateRows(List.of(), List.of(), List.of(), List.of());
        }

        var hotTripsById = new HashMap<Long, HotTripRow>(descriptors.size() * 2 + 16);

        var latestDescriptors = latestDescriptors(descriptors, hotTripsById);
        var latestStops = latestStops(stopTimes, hotTripsById);

        return new TripUpdateRows(latestDescriptors, stopTimes, new ArrayList<>(hotTripsById.values()), latestStops);
    }

    /**
     * Loads the static stops once for every trip that needs them, whether for sequence resolution
     * or for the effective delay. A feed that needs both does not read the static tables twice.
     */
    private Map<TripKey, List<StaticStopTime>> schedulesFor(List<TripUpdateDto> batch) {
        var keys = new HashSet<TripKey>();
        keys.addAll(StopSequenceResolver.keysNeedingLookup(batch));
        keys.addAll(EffectiveDelayResolver.keysNeedingLookup(batch));
        if (keys.isEmpty()) {
            return Map.of();
        }
        return staticStopTimes.load(selectionsFor(batch, keys));
    }

    /** The stop ids and sequences each needed trip refers to, merged over the batch's updates. */
    private static Map<TripKey, StopSelection> selectionsFor(List<TripUpdateDto> batch, Set<TripKey> keys) {
        var selections = new HashMap<TripKey, StopSelection>(keys.size() * 2 + 16);
        for (var update : batch) {
            var descriptor = update.tripDescriptor();
            if (descriptor == null || update.stopTimeUpdates() == null) {
                continue;
            }
            var key = new TripKey(descriptor.feedId(), descriptor.tripId());
            if (!keys.contains(key)) {
                continue;
            }
            var selection = selections.computeIfAbsent(key, k -> new StopSelection(new HashSet<>(), new HashSet<>()));
            for (var row : update.stopTimeUpdates()) {
                if (row.stopId() != null) {
                    selection.stopIds().add(row.stopId());
                }
                if (row.stopSequence() != null) {
                    selection.sequences().add(row.stopSequence());
                }
            }
        }
        return selections;
    }

    /**
     * Upper bound on the batch's stop-time row count, used to size the flatten list once so it never
     * has to grow and copy. Rows without a stop sequence are counted here but dropped during the
     * flatten, so the result is a capacity, not an exact size.
     */
    private static int stopTimeRowCount(List<TripUpdateDto> batch) {
        var count = 0;
        for (var dto : batch) {
            var rows = dto.stopTimeUpdates();
            if (rows != null) {
                count += rows.size();
            }
        }
        return count;
    }

    /**
     * Keeps one descriptor per trip id (the latest feed timestamp), matching the PK of
     * {@code rt_trip_updates_meta}, and in the same pass feeds each trip into the shared
     * current-state map.
     */
    private static List<TripDescriptorDto> latestDescriptors(
            List<TripDescriptorDto> descriptors, HashMap<Long, HotTripRow> hotTripsById) {
        var byId = new HashMap<Long, TripDescriptorDto>(descriptors.size() * 2 + 16);

        for (var descriptor : descriptors) {
            var existing = byId.get(descriptor.id());

            if (existing == null || descriptor.feedTs().isAfter(existing.feedTs())) {
                byId.put(descriptor.id(), descriptor);
            }

            mergeLatest(hotTripsById, descriptor.id(), descriptor.feedId(), descriptor.feedTs());
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Keeps one row per (trip, stop sequence) (the latest feed timestamp), matching the PK of
     * {@code rt_stop_time_updates_hot}, and in the same pass feeds each trip into the shared
     * current-state map.
     */
    private static List<TripStopTimeUpdateDto> latestStops(
            List<TripStopTimeUpdateDto> stopTimes, HashMap<Long, HotTripRow> hotTripsById) {
        var byStop = new HashMap<StopKey, TripStopTimeUpdateDto>(stopTimes.size() * 2 + 16);

        for (var update : stopTimes) {
            var key = new StopKey(update.tripKey(), update.stopSequence());
            var existing = byStop.get(key);

            if (existing == null || update.feedTs().isAfter(existing.feedTs())) {
                byStop.put(key, update);
            }

            mergeLatest(hotTripsById, update.tripKey(), update.feedId(), update.feedTs());
        }
        return new ArrayList<>(byStop.values());
    }

    /**
     * Keeps the latest feed timestamp seen for a trip id, matching the PK of
     * {@code rt_trip_updates_hot}.
     */
    private static void mergeLatest(
            HashMap<Long, HotTripRow> hotTripsById, long tripUpdateId, String feedId, Instant feedTs) {
        var existing = hotTripsById.get(tripUpdateId);

        if (existing == null || feedTs.isAfter(existing.feedTs())) {
            hotTripsById.put(tripUpdateId, new HotTripRow(tripUpdateId, feedId, feedTs));
        }
    }

    /** Conflict key of {@code rt_stop_time_updates_hot}: (trip_update_id, stop_sequence). */
    private record StopKey(long tripKey, Integer stopSequence) {}
}
