package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.example.gtfsynq.shared.protocol.PollStatus;
import org.example.gtfsynq.shared.protocol.RealtimeFeedPolled;

/**
 * Persistence for the polling state of GTFS-RT feeds, one row per feed and URL.
 *
 * <p>Validators and size only change on a poll that actually reached the origin: a failed poll
 * keeps the last known ones and only moves the status and poll time. An older event never
 * overwrites a newer row.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class RealtimeFeedStateRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO realtime_feed_state (
            feed_id, source_url, source_etag, source_last_modified,
            last_status, size_bytes, last_changed_at, last_polled_at)
        VALUES (?, ?, ?, ?, CAST(? AS realtime_poll_status), ?, CASE WHEN ? THEN ? END, ?)
        ON CONFLICT (feed_id, source_url) DO UPDATE
        SET source_etag = CASE WHEN ? THEN EXCLUDED.source_etag ELSE realtime_feed_state.source_etag END,
            source_last_modified = CASE WHEN ? THEN EXCLUDED.source_last_modified
                                        ELSE realtime_feed_state.source_last_modified END,
            last_status = EXCLUDED.last_status,
            size_bytes = CASE WHEN ? THEN EXCLUDED.size_bytes ELSE realtime_feed_state.size_bytes END,
            last_changed_at = CASE WHEN ? THEN EXCLUDED.last_polled_at ELSE realtime_feed_state.last_changed_at END,
            last_polled_at = EXCLUDED.last_polled_at
        WHERE realtime_feed_state.last_polled_at <= EXCLUDED.last_polled_at
        """;

    private final DataSource dataSource;

    /**
     * Records the outcome of a poll.
     *
     * @param event poll outcome from the ingest app
     */
    public void upsert(RealtimeFeedPolled event) {
        var status = event.getStatus();
        if (status == PollStatus.UNRECOGNIZED || status == PollStatus.POLL_STATUS_UNSPECIFIED) {
            throw new IllegalArgumentException("Unusable poll status " + status + " for " + event.getFeedId());
        }
        var reachedOrigin = status == PollStatus.POLL_STATUS_OK || status == PollStatus.POLL_STATUS_NOT_MODIFIED;
        var changed = status == PollStatus.POLL_STATUS_OK;
        var polledAt = Timestamp.from(Instant.ofEpochSecond(event.getPolledAtEpochSeconds()));

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(UPSERT_SQL)) {
            statement.setString(1, event.getFeedId());
            statement.setString(2, event.getSourceUrl());
            statement.setString(3, emptyToNull(event.getSourceEtag()));
            statement.setString(4, emptyToNull(event.getSourceLastModified()));
            statement.setString(5, status.name().substring("POLL_STATUS_".length()));
            statement.setLong(6, event.getSizeBytes());
            statement.setBoolean(7, changed);
            statement.setTimestamp(8, polledAt);
            statement.setTimestamp(9, polledAt);
            statement.setBoolean(10, reachedOrigin);
            statement.setBoolean(11, reachedOrigin);
            statement.setBoolean(12, changed);
            statement.setBoolean(13, changed);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record realtime feed state for " + event.getFeedId(), e);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
