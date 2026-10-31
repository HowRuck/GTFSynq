package org.example.gtfsynq.ingest.service;

import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedValidators;

/**
 * Remembers the HTTP validators of the last successful poll of each static feed.
 * <p>
 * The validators are held in memory: the first poll after a restart transfers the archive once,
 * and the content-addressed object store already makes that download cheap to absorb (the digest
 * is found and nothing is re-uploaded). Persisting them would require a second source of truth
 * that has to be kept in step with object storage, which is not worth a once-per-restart
 * download.
 * <p>
 * Entries are keyed by feed id and scoped to the configured URL, so changing a feed's
 * {@code static-config.url} ignores validators that describe a different origin.
 */
@Singleton
public class StaticFeedValidatorCache {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Looks up the validators of a feed.
     *
     * @param feedId feed source identifier
     * @param sourceUrl configured source URL
     * @return the validators from the last successful poll, or empty validators
     */
    public StaticFeedValidators get(String feedId, String sourceUrl) {
        var entry = entries.get(feedId);

        if (entry == null || !entry.sourceUrl().equals(sourceUrl)) {
            return StaticFeedValidators.none();
        }

        return entry.validators();
    }

    /**
     * Stores the validators of a poll that was processed successfully. A poll that failed before
     * the revision reached object storage must not be remembered, otherwise the next conditional
     * request would answer {@code 304} for a revision that was never stored.
     *
     * @param feedId feed source identifier
     * @param sourceUrl configured source URL
     * @param validators validators from the response
     */
    public void put(String feedId, String sourceUrl, StaticFeedValidators validators) {
        if (validators == null || validators.isEmpty()) {
            entries.remove(feedId);
            return;
        }

        entries.put(feedId, new Entry(sourceUrl, validators));
    }

    /**
     * Forgets the validators of a feed, forcing the next poll to transfer the archive.
     *
     * @param feedId feed source identifier
     */
    public void forget(String feedId) {
        entries.remove(feedId);
    }

    private record Entry(String sourceUrl, StaticFeedValidators validators) {}
}
