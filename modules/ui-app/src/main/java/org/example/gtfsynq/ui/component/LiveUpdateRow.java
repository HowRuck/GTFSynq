package org.example.gtfsynq.ui.component;

import org.example.gtfsynq.shared.protocol.query.StopTimeUpdate;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;

/** A row in the live updates tree: a trip update, or one of its stop time updates. */
public sealed interface LiveUpdateRow permits TripUpdateRow, StopTimeUpdateRow {

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
