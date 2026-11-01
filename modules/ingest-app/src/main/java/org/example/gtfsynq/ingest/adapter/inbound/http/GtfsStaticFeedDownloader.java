package org.example.gtfsynq.ingest.adapter.inbound.http;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.config.HttpTimeouts;
import org.example.gtfsynq.shared.util.SizeFormat;

/**
 * Downloads GTFS static feed archives
 * <p>
 * Static archives are an order of magnitude larger than GTFS-RT payloads, so they are
 * streamed straight to a temporary file while the SHA-256 digest is computed on the fly
 * instead of being buffered in memory
 * <p>
 * When the previous response carried an {@code ETag} or {@code Last-Modified}, the request is
 * made conditional. An origin that recognises the validator answers {@code 304 Not Modified}
 * and no archive is transferred at all, which is what makes a five-minute poll of an unchanged
 * feed nearly free.
 *
 * <p>A {@code 429 Too Many Requests} answer is reported as {@link StaticFeedRateLimitedException}
 * carrying the origin's {@code Retry-After}, so the caller can back off instead of treating a
 * deliberate throttle as a failure.
 */
@ApplicationScoped
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedDownloader {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final String TEMP_FILE_PREFIX = "gtfs-static-";

    private static final String TEMP_FILE_SUFFIX = ".zip";

    private static final String IF_NONE_MATCH = "If-None-Match";

    private static final String IF_MODIFIED_SINCE = "If-Modified-Since";

    private static final String ETAG = "ETag";

    private static final String LAST_MODIFIED = "Last-Modified";

    private static final String RETRY_AFTER = "Retry-After";

    private static final int NOT_MODIFIED = 304;

    private static final int TOO_MANY_REQUESTS = 429;

    private final HttpClient httpClient;
    private final HttpTimeouts httpTimeouts;

    /**
     * Downloads a static feed to a temporary file, revalidating it against the given validators.
     *
     * @param feedId feed source identifier, used for logging
     * @param sourceUrl URL of the static feed archive
     * @param validators validators from the previous poll, or {@link StaticFeedValidators#none()}
     * @return the downloaded archive, or {@code 304 Not Modified}; the caller owns the temporary
     *     file and must hand it back via {@link #discard(DownloadedStaticFeed)} when done with it
     * @throws StaticFeedDownloadException when the archive could not be fetched
     */
    @WithSpan
    public StaticFeedDownload download(String feedId, String sourceUrl, StaticFeedValidators validators) {
        validators = Objects.requireNonNullElse(validators, StaticFeedValidators.none());

        try {
            var request = request(sourceUrl, validators);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream body = response.body()) {
                var statusCode = response.statusCode();

                if (statusCode == NOT_MODIFIED) {
                    var merged = validators.merge(validatorsOf(response));

                    log.info(
                            "Static feed {} from {} was not modified (etag={}, lastModified={})",
                            feedId,
                            sourceUrl,
                            merged.etag(),
                            merged.lastModified());

                    return new StaticFeedDownload.NotModified(merged);
                }

                if (statusCode == TOO_MANY_REQUESTS) {
                    // Not an error: the origin is asking us to slow down. The caller decides how
                    // long to wait, using Retry-After when the origin sent one.
                    throw new StaticFeedRateLimitedException(
                            "Rate limited (HTTP 429) while downloading static feed " + feedId + " from " + sourceUrl,
                            parseRetryAfter(
                                    response.headers().firstValue(RETRY_AFTER).orElse(null)));
                }

                if (statusCode < 200 || statusCode >= 300) {
                    log.warn(
                            "Unexpected HTTP {} while downloading static feed {} from {}",
                            statusCode,
                            feedId,
                            sourceUrl);
                    throw new StaticFeedDownloadException("Unexpected HTTP " + statusCode
                            + " while downloading static feed " + feedId + " from " + sourceUrl);
                }

                return new StaticFeedDownload.Downloaded(downloadInto(feedId, sourceUrl, body, validatorsOf(response)));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", e);
        } catch (IOException e) {
            log.error("Failed to download static feed {} from {}", feedId, sourceUrl, e);
            throw new StaticFeedDownloadException("Failed to download static feed " + feedId + " from " + sourceUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to download static feed {} from {}", feedId, sourceUrl, e);
            throw new StaticFeedDownloadException("Failed to download static feed " + feedId + " from " + sourceUrl, e);
        }
    }

    /**
     * Downloads a static feed without sending conditional headers.
     *
     * @param feedId feed source identifier, used for logging
     * @param sourceUrl URL of the static feed archive
     * @return the downloaded archive
     * @throws StaticFeedDownloadException when the archive could not be fetched
     */
    public DownloadedStaticFeed download(String feedId, String sourceUrl) {
        var download = download(feedId, sourceUrl, StaticFeedValidators.none());

        return switch (download) {
            case StaticFeedDownload.Downloaded downloaded -> downloaded.feed();
            case StaticFeedDownload.NotModified ignored ->
                throw new StaticFeedDownloadException("Static feed " + feedId + " from " + sourceUrl
                        + " answered 304 Not Modified to an unconditional request");
        };
    }

    /**
     * Deletes the temporary file backing a downloaded archive
     *
     * @param feed archive to discard
     */
    public void discard(DownloadedStaticFeed feed) {
        discard(feed.file());
    }

    private HttpRequest request(String sourceUrl, StaticFeedValidators validators) {
        var builder = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofMillis(httpTimeouts.readMs()))
                .GET();

        if (validators.etag() != null) {
            builder.header(IF_NONE_MATCH, validators.etag());
        }

        if (validators.lastModified() != null) {
            builder.header(IF_MODIFIED_SINCE, validators.lastModified());
        }

        return builder.build();
    }

    private DownloadedStaticFeed downloadInto(
            String feedId, String sourceUrl, InputStream body, StaticFeedValidators validators)
            throws IOException, NoSuchAlgorithmException {
        Path target = null;
        var handedOver = false;

        try {
            target = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);

            var digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            var sizeBytes = transferInto(body, target, digest);

            if (sizeBytes <= 0) {
                throw new StaticFeedDownloadException(
                        "Static feed " + feedId + " downloaded from " + sourceUrl + " was empty");
            }

            var sha256 = HexFormat.of().formatHex(digest.digest());

            log.info(
                    "Downloaded static feed {} from {} of size {} (sha256={})",
                    feedId,
                    sourceUrl,
                    SizeFormat.humanBytes(sizeBytes),
                    sha256);

            handedOver = true;

            return new DownloadedStaticFeed(target, sha256, sizeBytes, validators);
        } finally {
            if (!handedOver) {
                discard(target);
            }
        }
    }

    private static long transferInto(InputStream body, Path target, MessageDigest digest) throws IOException {
        try (var in = new BufferedInputStream(body);
                var out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
            return in.transferTo(out);
        }
    }

    private static StaticFeedValidators validatorsOf(HttpResponse<?> response) {
        return new StaticFeedValidators(
                response.headers().firstValue(ETAG).orElse(null),
                response.headers().firstValue(LAST_MODIFIED).orElse(null));
    }

    /**
     * Parses a {@code Retry-After} header, which the HTTP specification allows as either a number
     * of seconds or an HTTP date.
     *
     * @param value header value, or {@code null}
     * @return the delay requested by the origin, or {@code null} when absent or unparsable
     */
    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed)));
        } catch (NumberFormatException _) {
            // Not an integer; the specification also allows an HTTP date.
        }
        try {
            var at = OffsetDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Duration.between(Instant.now(), at.toInstant());
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private void discard(Path file) {
        if (file == null) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete temporary static feed file {}", file, e);
        }
    }

    /**
     * A downloaded static feed archive staged in a temporary file
     *
     * @param file temporary file holding the archive
     * @param sha256 hex encoded SHA-256 digest of the archive
     * @param sizeBytes size of the archive in bytes
     * @param validators HTTP cache validators to send on the next poll
     */
    public record DownloadedStaticFeed(Path file, String sha256, long sizeBytes, StaticFeedValidators validators) {}
}
