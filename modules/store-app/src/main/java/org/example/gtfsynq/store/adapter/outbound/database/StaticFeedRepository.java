package org.example.gtfsynq.store.adapter.outbound.database;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.store.service.metrics.StaticPersistenceMetrics;

/**
 * Persistence for GTFS static feed revisions.
 *
 * <p>A feed is versioned by the digest of its archive, and only one revision per feed is current.
 * {@link #openRevision} therefore compares the digest the event carries against the one
 * {@code static_feeds} records: an event for the revision already loaded opens a writer that drops
 * the rows it is handed, and any other event first clears the feed's rows so the new revision
 * replaces the old one instead of merging with it. Clearing and writing share a transaction, so a
 * feed is never left half-replaced.
 *
 * <p>Loads are serialised per feed with a transaction-scoped advisory lock, so two events for the
 * same feed cannot interleave their delete and insert even if they are consumed concurrently.
 * Locks on different feeds never block each other.
 */
@ApplicationScoped
@RequiredArgsConstructor
@Slf4j
public class StaticFeedRepository {

    /** Tables holding static rows, in the order they are cleared. */
    private static final List<String> ROW_TABLES = List.of(
            StaticFeedWriter.STOP_TIMES,
            StaticFeedWriter.TRIPS,
            StaticFeedWriter.ROUTES,
            StaticFeedWriter.STOPS,
            StaticFeedWriter.CALENDAR_DATES,
            StaticFeedWriter.CALENDAR,
            StaticFeedWriter.AGENCY);

    private static final String LOCK_FEED_SQL = """
            SELECT pg_advisory_xact_lock(hashtext(?))
            """;

    private static final String CURRENT_DIGEST_SQL = """
            SELECT sha256
            FROM static_feeds
            WHERE feed_id = ?
            """;

    private final DataSource dataSource;

    private final StaticPersistenceMetrics metrics;

    /**
     * Opens a writer for the revision an event describes, clearing the feed's current rows first.
     *
     * @param event ingestion event carrying the feed id, digest and archive location
     * @return a writer the caller streams the archive into and then commits
     * @throws IllegalStateException if the revision cannot be opened, in which case nothing changed
     */
    public StaticFeedWriter openRevision(StaticFeedIngested event) {
        try {
            Connection connection;
            var connectionStart = System.nanoTime();
            try {
                connection = dataSource.getConnection();
            } finally {
                metrics.recordTransactionPhase(
                        StaticPersistenceMetrics.TransactionPhase.CONNECTION, System.nanoTime() - connectionStart);
            }
            var handedOver = false;
            try {
                connection.setAutoCommit(false);
                lockFeed(connection, event.getFeedId());
                var unchanged = isCurrentRevision(connection, event);
                if (!unchanged) {
                    clearFeed(connection, event.getFeedId());
                }
                var writer = new StaticFeedWriter(connection, event, unchanged, metrics);
                handedOver = true;
                return writer;
            } catch (SQLException e) {
                rollback(connection);
                throw e;
            } finally {
                if (!handedOver) {
                    close(connection);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not open static revision for feed " + event.getFeedId(), e);
        }
    }

    private void lockFeed(Connection connection, String feedId) throws SQLException {
        var start = System.nanoTime();
        try (PreparedStatement preparedStatement = connection.prepareStatement(LOCK_FEED_SQL)) {
            // hashtext is the database's own string hash, so the lock name needs no second hashing
            // scheme here and stays identical across processes for the same feed id.
            preparedStatement.setString(1, feedId);
            preparedStatement.executeQuery().close();
        } finally {
            metrics.recordTransactionPhase(
                    StaticPersistenceMetrics.TransactionPhase.ADVISORY_LOCK, System.nanoTime() - start);
        }
    }

    private boolean isCurrentRevision(Connection connection, StaticFeedIngested event) throws SQLException {
        var start = System.nanoTime();
        try (PreparedStatement preparedStatement = connection.prepareStatement(CURRENT_DIGEST_SQL)) {
            preparedStatement.setObject(1, event.getFeedId(), Types.OTHER);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                var loaded = resultSet.getString(1);
                return loaded != null && loaded.equalsIgnoreCase(event.getSha256());
            }
        } finally {
            metrics.recordTransactionPhase(
                    StaticPersistenceMetrics.TransactionPhase.REVISION_LOOKUP, System.nanoTime() - start);
        }
    }

    private void clearFeed(Connection connection, String feedId) throws SQLException {
        var start = System.nanoTime();
        try {
            for (var table : ROW_TABLES) {
                try (PreparedStatement preparedStatement =
                        connection.prepareStatement("DELETE FROM " + table + " WHERE feed_id = ?")) {
                    preparedStatement.setObject(1, feedId, Types.OTHER);
                    var deleted = preparedStatement.executeUpdate();
                    if (deleted > 0) {
                        log.debug(
                                "Cleared {} rows of the previous revision of feed {} from {}", deleted, feedId, table);
                    }
                }
            }
        } finally {
            metrics.recordTransactionPhase(StaticPersistenceMetrics.TransactionPhase.CLEAR, System.nanoTime() - start);
        }
    }

    private void rollback(Connection connection) {
        var start = System.nanoTime();
        try {
            connection.rollback();
        } catch (SQLException e) {
            // The connection is discarded afterwards; Agroal resets it on return to the pool.
        } finally {
            metrics.recordTransactionPhase(
                    StaticPersistenceMetrics.TransactionPhase.ROLLBACK, System.nanoTime() - start);
        }
    }

    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            // Nothing left to hand back cleanly, and the pool reaps connections on its own.
        }
    }
}
