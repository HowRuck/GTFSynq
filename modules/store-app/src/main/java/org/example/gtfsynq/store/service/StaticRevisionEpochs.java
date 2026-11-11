package org.example.gtfsynq.store.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Identifies, per feed, the static revision the realtime state was last written against.
 *
 * <p>The realtime deduplication keeps one entry per stop and skips a stop whose prediction has not
 * moved. A delay derived from the static plan depends on the plan too, so a stop written before a
 * revision landed (or before the first one did) would otherwise keep the delay it was stored with
 * until its prediction moved. The deduplication mixes this epoch into its keys: when a revision is
 * committed the feed's keys change, every stop looks new once, and the next poll rewrites it
 * against the new plan. The old entries are never touched and expire with the store's TTL.
 *
 * <p>The epoch is a hash of the revision's digest in {@code static_feeds}, not a counter, so it is
 * the same after a restart and on every instance: it is read from the database the first time a
 * feed is seen and updated when this process commits a revision.
 */
@Singleton
@Slf4j
public class StaticRevisionEpochs {

    private static final String DIGEST_SQL = "SELECT sha256 FROM static_feeds WHERE feed_id = CAST(? AS feed_id_enum)";

    private final ConcurrentMap<String, Integer> epochs = new ConcurrentHashMap<>();

    private final Function<String, String> digestLoader;

    @Inject
    public StaticRevisionEpochs(DataSource dataSource) {
        this(feedId -> loadDigest(dataSource, feedId));
    }

    /** Reads digests from the given function; for callers without a database. */
    public StaticRevisionEpochs(Function<String, String> digestLoader) {
        this.digestLoader = digestLoader;
    }

    /**
     * The feed's current epoch; zero while the feed has no loaded revision, which keeps the keys
     * written before any static data existed valid.
     *
     * <p>A failed read is not cached and answers zero for now: the next call asks again.
     */
    public int current(String feedId) {
        if (feedId == null) {
            return 0;
        }
        var known = epochs.get(feedId);
        if (known != null) {
            return known;
        }
        try {
            var epoch = epochOf(digestLoader.apply(feedId));
            var raced = epochs.putIfAbsent(feedId, epoch);
            return raced != null ? raced : epoch;
        } catch (RuntimeException e) {
            log.warn("Could not read the static revision of feed {}; deduplicating without it", feedId, e);
            return 0;
        }
    }

    /** Records that the feed now holds the revision with this digest. */
    public void revisionCommitted(String feedId, String sha256) {
        epochs.put(feedId, epochOf(sha256));
    }

    private static int epochOf(String sha256) {
        if (sha256 == null) {
            return 0;
        }
        var hash = sha256.toLowerCase(java.util.Locale.ROOT).hashCode();
        return hash == 0 ? 1 : hash;
    }

    private static String loadDigest(DataSource dataSource, String feedId) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(DIGEST_SQL)) {
            statement.setString(1, feedId);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read static_feeds for feed " + feedId, e);
        }
    }
}
