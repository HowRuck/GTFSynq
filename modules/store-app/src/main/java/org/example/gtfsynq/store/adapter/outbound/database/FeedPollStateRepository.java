package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.example.gtfsynq.shared.protocol.FeedPolled;
import org.example.gtfsynq.shared.protocol.PollStatus;

/**
 * Persistence for the polling state of static and realtime feeds, one row per kind, feed and URL.
 *
 * <p>Validators and size only change on a poll that actually reached the origin: a failed poll
 * keeps the last known ones and only moves the status and poll time. An older event never
 * overwrites a newer row.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class FeedPollStateRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO feed_poll_state (
            kind, feed_id, source_url, source_etag, source_last_modified,
            last_status, size_bytes, last_changed_at, last_polled_at, sha256)
        VALUES (CAST(? AS feed_kind), ?, ?, ?, ?, CAST(? AS feed_poll_status), ?, CASE WHEN ? THEN CAST(? AS timestamptz) END, ?, ?)
        ON CONFLICT (kind, feed_id, source_url) DO UPDATE
        SET source_etag = CASE WHEN ? THEN EXCLUDED.source_etag ELSE feed_poll_state.source_etag END,
            source_last_modified = CASE WHEN ? THEN EXCLUDED.source_last_modified
                                        ELSE feed_poll_state.source_last_modified END,
            last_status = EXCLUDED.last_status,
            size_bytes = CASE WHEN ? THEN EXCLUDED.size_bytes ELSE feed_poll_state.size_bytes END,
            sha256 = CASE WHEN ? THEN EXCLUDED.sha256 ELSE feed_poll_state.sha256 END,
            last_changed_at = CASE WHEN ? THEN EXCLUDED.last_polled_at ELSE feed_poll_state.last_changed_at END,
            last_polled_at = EXCLUDED.last_polled_at
        WHERE feed_poll_state.last_polled_at <= EXCLUDED.last_polled_at
        """;

    private final DataSource dataSource;

    /**
     * Records the outcome of a poll.
     *
     * @param event poll outcome from the ingest app
     */
    public void upsert(FeedPolled event) {
        var status = event.getStatus();
        statusName(event);
        var reachedOrigin = status == PollStatus.POLL_STATUS_OK || status == PollStatus.POLL_STATUS_NOT_MODIFIED;
        var changed = status == PollStatus.POLL_STATUS_OK;
        var polledAt = Timestamp.from(Instant.ofEpochSecond(event.getPolledAtEpochSeconds()));

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(UPSERT_SQL)) {
            statement.setString(1, kindName(event));
            statement.setString(2, event.getFeedId());
            statement.setString(3, event.getSourceUrl());
            statement.setString(4, emptyToNull(event.getSourceEtag()));
            statement.setString(5, emptyToNull(event.getSourceLastModified()));
            statement.setString(6, statusName(event));
            statement.setLong(7, event.getSizeBytes());
            statement.setBoolean(8, changed);
            statement.setTimestamp(9, polledAt);
            statement.setTimestamp(10, polledAt);
            statement.setString(11, emptyToNull(event.getSha256()));
            statement.setBoolean(12, reachedOrigin);
            statement.setBoolean(13, reachedOrigin);
            statement.setBoolean(14, changed);
            statement.setBoolean(15, changed);
            statement.setBoolean(16, changed);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record feed poll state for " + event.getFeedId(), e);
        }
    }

    private static String kindName(FeedPolled event) {
        return switch (event.getKind()) {
            case FEED_KIND_STATIC -> "static";
            case FEED_KIND_REALTIME -> "realtime";
            case FEED_KIND_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("Unusable feed kind for " + event.getFeedId());
        };
    }

    private static String statusName(FeedPolled event) {
        var status = event.getStatus();
        if (status == PollStatus.UNRECOGNIZED || status == PollStatus.POLL_STATUS_UNSPECIFIED) {
            throw new IllegalArgumentException("Unusable poll status " + status + " for " + event.getFeedId());
        }
        return status.name().substring("POLL_STATUS_".length());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
