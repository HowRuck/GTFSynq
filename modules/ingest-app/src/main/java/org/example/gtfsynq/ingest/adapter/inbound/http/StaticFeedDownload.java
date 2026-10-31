package org.example.gtfsynq.ingest.adapter.inbound.http;

/**
 * Outcome of a static feed request.
 * <p>
 * A poll can end with a full archive or with {@code 304 Not Modified}. Both carry the validators
 * to use on the next poll, so the caller can keep them even when there is nothing to store.
 */
public sealed interface StaticFeedDownload {

    /**
     * @return validators to send on the next request for the feed
     */
    StaticFeedValidators validators();

    /**
     * The origin served a complete archive.
     *
     * @param feed downloaded archive staged in a temporary file
     */
    record Downloaded(GtfsStaticFeedDownloader.DownloadedStaticFeed feed) implements StaticFeedDownload {

        @Override
        public StaticFeedValidators validators() {
            return feed.validators();
        }
    }

    /**
     * The origin answered {@code 304 Not Modified}, so the stored revision is still current.
     *
     * @param validators validators to send on the next request
     */
    record NotModified(StaticFeedValidators validators) implements StaticFeedDownload {}
}
