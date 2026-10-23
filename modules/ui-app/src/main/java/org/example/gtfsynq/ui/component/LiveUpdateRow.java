package org.example.gtfsynq.ui.component;

import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;

/**
 * A row in the live updates tree: a trip update, one of its stop time updates, or a placeholder
 * shown while the stop time updates are loading.
 */
public sealed interface LiveUpdateRow permits TripUpdateRow, StopTimeUpdateRow, LoadingRow {

    /** Identifier of the trip update this row belongs to. */
    long tripUpdateId();
}

/** A trip update; expanding it loads its stop time updates. */
record TripUpdateRow(TripUpdate update) implements LiveUpdateRow {

    @Override
    public long tripUpdateId() {
        return update.getTripUpdateId();
    }
}

/** A stop time update below its trip update. */
record StopTimeUpdateRow(long tripUpdateId, StopTimeUpdate update) implements LiveUpdateRow {}

/** Placeholder child shown under a trip while its stop time updates are being fetched. */
record LoadingRow(long tripUpdateId) implements LiveUpdateRow {}
