package org.example.gtfsynq.ui.format;

import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.example.gtfsynq.shared.protocol.query.TripUpdate;

/**
 * Where a live trip is in its run, derived from the scheduled start, the scheduled final stop and
 * the time the feed last reported it.
 *
 * <p>Checked in this order: {@link #SCHEDULED} while the start is in the future, {@link #FINISHED}
 * once the final scheduled stop has passed, {@link #STALE} when the feed has not reported the trip
 * for {@link #STALE_AFTER}, and {@link #UNDERWAY} otherwise. A trip with no final stop time cannot
 * be finished, so it is stale or underway.
 */
public enum TripStatus {
    SCHEDULED("trip.status.scheduled", null),
    UNDERWAY("trip.status.underway", null),
    FINISHED("trip.status.finished", "trip.status.finished.title"),
    STALE("trip.status.stale", "trip.status.stale.title");

    /** How long a trip may go unreported before it is shown as stale. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final String labelKey;
    private final String titleKey;

    TripStatus(String labelKey, String titleKey) {
        this.labelKey = labelKey;
        this.titleKey = titleKey;
    }

    /** Translation key of the status name. */
    public String labelKey() {
        return labelKey;
    }

    /** Translation key of the explanation shown on hover, or {@code null} when none is needed. */
    public String titleKey() {
        return titleKey;
    }

    /** The status of {@code trip} at {@code now}. */
    public static TripStatus of(TripUpdate trip, Instant now) {
        var start = instant(trip.hasScheduledStartAt(), trip.getScheduledStartAt());
        if (start != null && start.isAfter(now)) {
            return SCHEDULED;
        }
        var lastStop = instant(trip.hasLastStopAt(), trip.getLastStopAt());
        if (lastStop != null && !lastStop.isAfter(now)) {
            return FINISHED;
        }
        var lastSeen = instant(trip.hasLastSeenAt(), trip.getLastSeenAt());
        if (lastSeen == null || lastSeen.plus(STALE_AFTER).isBefore(now)) {
            return STALE;
        }
        return UNDERWAY;
    }

    private static Instant instant(boolean present, Timestamp timestamp) {
        return present ? Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()) : null;
    }
}
