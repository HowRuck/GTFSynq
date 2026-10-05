package org.example.gtfsynq.store.adapter.outbound.database;

import java.time.Instant;
import java.util.List;
import org.example.gtfsynq.shared.model.dto.TripDescriptorDto;
import org.example.gtfsynq.shared.model.dto.TripStopTimeUpdateDto;

/**
 * The row streams produced from one drained sink batch, ready to be written by
 * {@link TripUpdateRepository}.
 *
 * <p>Each list is already shaped for its target table. The two {@code ON CONFLICT DO UPDATE}
 * streams (trip metadata and the hot tables) are collapsed by their conflict keys, so a batch
 * rewrite via {@code reWriteBatchedInserts} never targets the same row twice. The history stream is
 * append-only ({@code DO NOTHING}) and keeps its rows as-is.
 *
 * @param descriptors      one row per distinct trip id for {@code rt_trip_updates_meta}
 * @param historyStopTimes append-only rows for {@code rt_stop_time_updates_ht}
 * @param hotTripRows      one row per distinct trip id for {@code rt_trip_updates_hot}
 * @param hotStopTimes     one row per distinct (trip, stop sequence) for {@code rt_stop_time_updates_hot}
 */
public record TripUpdateRows(
        List<TripDescriptorDto> descriptors,
        List<TripStopTimeUpdateDto> historyStopTimes,
        List<HotTripRow> hotTripRows,
        List<TripStopTimeUpdateDto> hotStopTimes) {

    /** Current-state trip row: (trip_update_id, feed_id, feed_ts). */
    public record HotTripRow(long tripUpdateId, String feedId, Instant feedTs) {}

    /** Whether this batch has anything to persist. */
    public boolean isEmpty() {
        return descriptors.isEmpty() && historyStopTimes.isEmpty();
    }
}
