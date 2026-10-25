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
 * <p>Loads are serialised per feed with a session-scoped advisory lock, because a revision spans
 * two transactions: the staging load and the short partition swap. Locks on different feeds never
 * block each other.
 */
@ApplicationScoped
@RequiredArgsConstructor
@Slf4j
public class StaticFeedRepository {

    /** Tables holding static rows; every one is partitioned by feed and swapped together. */
    private static final List<String> ROW_TABLES = List.of(
            StaticFeedWriter.STOP_TIMES,
            StaticFeedWriter.TRIPS,
            StaticFeedWriter.ROUTES,
            StaticFeedWriter.STOPS,
            StaticFeedWriter.CALENDAR_DATES,
            StaticFeedWriter.CALENDAR,
            StaticFeedWriter.AGENCY);

    /** Session-scoped lock: the revision spans two transactions (load, then swap). */
    private static final String LOCK_FEED_SQL = """
            SELECT pg_advisory_lock(hashtext(?))
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
                // The staging load is disposable until the swap commits; skipping its WAL fsync
                // removes the multi-minute wait at the end of a large COPY. The swap transaction
                // keeps the default, so the revision switch itself stays durable.
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL synchronous_commit = off");
                }
                lockFeed(connection, event.getFeedId());
                var unchanged = isCurrentRevision(connection, event);
                log.debug(
                        "Static revision for feed {} digest {}: unchanged={}",
                        event.getFeedId(),
                        shortDigest(event.getSha256()),
                        unchanged);
                StaticFeedWriter writer;
                if (unchanged) {
                    writer = new StaticFeedWriter(connection, event, true, metrics, null);
                } else {
                    log.info(
                            "Loading revision {} of feed {} into a staging schema for {}",
                            shortDigest(event.getSha256()),
                            event.getFeedId(),
                            event.getObjectKey());
                    var stagingSchema = stagingSchema(connection, event);
                    writer = new StaticFeedWriter(connection, event, false, metrics, stagingSchema);
                }
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

    private static String shortDigest(String sha256) {
        return sha256 == null || sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
    }

    /**
     * Creates an empty per-revision schema whose tables mirror the static tables, and points the
     * load transaction's search_path at it. The writer can then use the same unqualified SQL; the
     * swap later attaches these tables as partitions.
     */
    private String stagingSchema(Connection connection, StaticFeedIngested event) throws SQLException {
        var schema = "static_stage_" + event.getFeedId().replaceAll("[^A-Za-z0-9_]", "_") + "_"
                + shortDigest(event.getSha256());
        try (var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            statement.execute("CREATE SCHEMA " + schema);
            for (var table : ROW_TABLES) {
                statement.execute(
                        "CREATE TABLE " + schema + "." + table + " (LIKE public." + table + " INCLUDING ALL)");
                statement.execute("ALTER TABLE " + schema + "." + table + " ADD CONSTRAINT " + table
                        + "_feed_bound CHECK (feed_id = '" + event.getFeedId() + "')");
            }
            // Unqualified table names in the writer resolve to the staging copies for the load.
            statement.execute("SET LOCAL search_path TO " + schema + ", public");
        }
        return schema;
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
