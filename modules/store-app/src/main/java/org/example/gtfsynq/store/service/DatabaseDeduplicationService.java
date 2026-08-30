package org.example.gtfsynq.store.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;
import org.example.gtfsynq.shared.model.dto.TripUpdateDto;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapHashStore;
import org.example.gtfsynq.shared.protocol.offheap.OffHeapLongTable;
import org.example.gtfsynq.shared.util.FeedHashing;
import org.springframework.stereotype.Component;

/**
 * Service for deduplicating trip updates in the database
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DatabaseDeduplicationService {

    /** The off-heap hash store used for storing trip update state */
    private final OffHeapHashStore stateStore;

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
     * @return a new {@code TripUpdateDto} with duplicate stop time updates removed,
     *         or {@code null} for unchanged tripDescriptor/stopTimeUpdates
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

        var changedStopTimeUpdates = new ArrayList<TripStopTimeUpdateDto>(
                feedEntity.stopTimeUpdates().size());

        for (var stopUpdate : feedEntity.stopTimeUpdates()) {
            // Create a unique key for this stop in the trip
            var keyBytes = (tripKey + ":" + stopUpdate.stopSequence()).getBytes(StandardCharsets.UTF_8);
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

        // Return a new TripUpdateDto, using null for unchanged components
        return new TripUpdateDto(
                tripDescriptorChanged ? tripDescriptor : null,
                changedStopTimeUpdates.isEmpty() ? null : changedStopTimeUpdates);
    }
}
