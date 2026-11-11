package org.example.gtfsynq.store.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;
import org.example.gtfsynq.shared.util.FeedHashing;

/**
 * Service for deduplicating trip updates in the database
 */
@ApplicationScoped
@Slf4j
public class DatabaseDeduplicationService {

    /** The off-heap hash store used for storing trip update state */
    private final OffHeapHashStore stateStore;

    /** Changes a feed's keys when its static revision does, so its stops are rewritten once. */
    private final StaticRevisionEpochs revisionEpochs;

    @Inject
    public DatabaseDeduplicationService(OffHeapHashStore stateStore, StaticRevisionEpochs revisionEpochs) {
        this.stateStore = stateStore;
        this.revisionEpochs = revisionEpochs;
    }

    /** Deduplicates without static revision tracking; for callers that have no static data. */
    public DatabaseDeduplicationService(OffHeapHashStore stateStore) {
        this(stateStore, new StaticRevisionEpochs(feedId -> null));
    }

    /**
     * Cleans the state of a trip update by removing duplicate stop time updates
     * and updating the trip descriptor hash if it has changed.
     *
     * <p>This method performs deduplication by:
     * <ol>
     *   <li>Tracking trip descriptor changes via hash comparison</li>
     *   <li>For each stop time update, comparing its metadata hash against the stored state</li>
     *   <li>If metadata differs, the update is kept and state is updated</li>
     *   <li>If metadata is the same but delays differ by more than 60 seconds, the update is kept</li>
     * </ol>
     *
     * @param feedEntity the trip update to clean
     * @return a new {@code TripUpdateDto} with duplicate stop time updates removed, or {@code null}
     *         when neither the descriptor nor any stop-time update changed. The descriptor is always
     *         carried through, even when unchanged, because batch mapping needs the trip id to
     *         resolve stop sequences a feed may not publish.
     */
    public TripUpdateDto cleanState(TripUpdateDto feedEntity) {
        var tripDescriptor = feedEntity.tripDescriptor();
        var tripKey = tripDescriptor.id();

        var tripHash = stateStore.get(tripKey);
        var tripDescriptorChanged = false;

        // Check if trip descriptor has changed
        if (tripHash != tripDescriptor.hash()) {
            stateStore.put(tripKey, tripDescriptor.hash());
            tripDescriptorChanged = true;
        }

        // Epoch zero keeps the original key, so state written before any revision stays valid.
        var epoch = revisionEpochs.current(tripDescriptor.feedId());
        var stopKeyPrefix = epoch == 0 ? tripKey + ":" : tripKey + "@" + epoch + ":";

        var changedStopTimeUpdates = new ArrayList<TripStopTimeUpdateDto>(
                feedEntity.stopTimeUpdates().size());

        for (var stopUpdate : feedEntity.stopTimeUpdates()) {
            // Identify the stop within the trip. stop_sequence is preferred, but feeds are allowed
            // to publish only stop_id, and keying those on the absent sequence put every stop of
            // the trip on the same ":null" key.
            var keyBytes = (stopKeyPrefix + stopIdentity(stopUpdate)).getBytes(StandardCharsets.UTF_8);
            var keyHash = FeedHashing.hashBytes(keyBytes);

            // Get the metadata hash (excluding delays) for comparison
            var currentHash = stopUpdate.hash();
            var existingHashData = stateStore.getWithCustomSlots(keyHash);
            var existingMetaHash = existingHashData[0];

            // Handle null delays by treating them as 0
            var currentArrivalDelay = stopUpdate.arrivalDelay() != null ? stopUpdate.arrivalDelay() : 0;
            var currentDepartureDelay = stopUpdate.departureDelay() != null ? stopUpdate.departureDelay() : 0;

            // If metadata has changed or this is a new stop, keep the update
            if (existingMetaHash == OffHeapLongTable.EMPTY_VALUE || currentHash != existingMetaHash) {
                changedStopTimeUpdates.add(stopUpdate);
                stateStore.put(keyHash, currentHash, currentArrivalDelay, currentDepartureDelay);
                continue;
            }

            // Get existing delays from state store
            var existingArrivalDelay = (int) existingHashData[1];
            var existingDepartureDelay = (int) existingHashData[2];

            // Compare delay differences - if either delay differs by more than 60 seconds,
            // consider this a significant change and keep the update
            var largestDelayDiff = Math.max(
                    Math.abs(currentArrivalDelay - existingArrivalDelay),
                    Math.abs(currentDepartureDelay - existingDepartureDelay));

            if (largestDelayDiff > 60) {
                changedStopTimeUpdates.add(stopUpdate);
                stateStore.put(keyHash, currentHash, currentArrivalDelay, currentDepartureDelay);
            }
        }

        // Trim the list to its actual size
        changedStopTimeUpdates.trimToSize();

        // Fully-duplicate entity: nothing changed, signal with null so the
        // caller can drop it before buffering
        if (!tripDescriptorChanged && changedStopTimeUpdates.isEmpty()) {
            return null;
        }

        // Return a new TripUpdateDto, using null for unchanged components. The descriptor is kept
        // even when unchanged: the batch mapper resolves missing stop sequences against the static
        // schedule, and a trip id is what ties a stop update back to its trip.
        return new TripUpdateDto(tripDescriptor, changedStopTimeUpdates.isEmpty() ? null : changedStopTimeUpdates);
    }

    /**
     * Identity of one stop visit inside a trip: the published sequence when there is one, otherwise
     * the stop id. A trip that visits the same stop twice and publishes no sequence cannot be told
     * apart from its message alone, so both visits share a key; that only costs an update, since the
     * write itself is an idempotent upsert.
     */
    private static String stopIdentity(TripStopTimeUpdateDto update) {
        var stopId = update.stopId() == null ? "" : update.stopId();
        return update.stopSequence() != null ? update.stopSequence() + ":" + stopId : "stop:" + stopId;
    }
}
