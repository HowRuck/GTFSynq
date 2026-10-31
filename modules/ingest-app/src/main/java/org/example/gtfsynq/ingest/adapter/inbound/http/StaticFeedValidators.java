package org.example.gtfsynq.ingest.adapter.inbound.http;

/**
 * HTTP cache validators learned from a static feed's last response.
 * <p>
 * Static feeds are polled on a schedule measured in days, and most polls find the archive
 * unchanged. Sending the validators back as {@code If-None-Match} / {@code If-Modified-Since}
 * lets the origin answer with {@code 304 Not Modified}, which turns a full archive download
 * into a tiny header exchange.
 * <p>
 * Both values are kept exactly as the origin sent them: an entity tag may be weak
 * ({@code W/"..."}) or quoted, and a {@code Last-Modified} value is an HTTP date that must be
 * echoed back verbatim.
 *
 * @param etag {@code ETag} header value, or {@code null} when the origin did not send one
 * @param lastModified {@code Last-Modified} header value, or {@code null} when absent
 */
public record StaticFeedValidators(String etag, String lastModified) {

    public StaticFeedValidators {
        etag = normalize(etag);
        lastModified = normalize(lastModified);
    }

    /**
     * @return validators that send no conditional headers
     */
    public static StaticFeedValidators none() {
        return new StaticFeedValidators(null, null);
    }

    /**
     * @return {@code true} when neither an entity tag nor a modification date is known
     */
    public boolean isEmpty() {
        return etag == null && lastModified == null;
    }

    /**
     * Combines these validators with the ones from a response, preferring the response.
     * <p>
     * A {@code 304} may repeat the current validators, omit them entirely or refresh only one
     * of them; keeping the known value avoids losing the ability to revalidate on the next
     * poll.
     *
     * @param response validators parsed from the response, possibly empty
     * @return the validators to remember for the next poll
     */
    public StaticFeedValidators merge(StaticFeedValidators response) {
        if (response == null || response.isEmpty()) {
            return this;
        }

        return new StaticFeedValidators(
                response.etag != null ? response.etag : etag,
                response.lastModified != null ? response.lastModified : lastModified);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
