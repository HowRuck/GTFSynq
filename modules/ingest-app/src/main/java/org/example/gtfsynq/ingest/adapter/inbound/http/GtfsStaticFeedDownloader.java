package org.example.gtfsynq.ingest.adapter.inbound.http;

import jakarta.inject.Singleton;
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
import java.util.HexFormat;
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
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedDownloader {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final String TEMP_FILE_PREFIX = "gtfs-static-";

    private static final String TEMP_FILE_SUFFIX = ".zip";

    private final HttpClient httpClient;
    private final HttpTimeouts httpTimeouts;

    /**
     * Downloads a static feed to a temporary file
     *
     * @param feedId feed source identifier, used for logging
     * @param sourceUrl URL of the static feed archive
     * @return the downloaded archive; the caller owns the temporary file and must hand it
     *     back via {@link #discard(DownloadedStaticFeed)} when done with it
     * @throws StaticFeedDownloadException when the archive could not be fetched
     */
    public DownloadedStaticFeed download(String feedId, String sourceUrl) {
        Path target = null;
        var handedOver = false;

        try {
            target = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);

            var digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            var sizeBytes = downloadInto(feedId, sourceUrl, target, digest);

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

            return new DownloadedStaticFeed(target, sha256, sizeBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", e);
        } catch (IOException e) {
            log.error("Failed to download static feed {} from {}", feedId, sourceUrl, e);
            throw new StaticFeedDownloadException("Failed to download static feed " + feedId + " from " + sourceUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to download static feed {} from {}", feedId, sourceUrl, e);
            throw new StaticFeedDownloadException("Failed to download static feed " + feedId + " from " + sourceUrl, e);
        } finally {
            if (!handedOver) {
                discard(target);
            }
        }
    }

    /**
     * Deletes the temporary file backing a downloaded archive
     *
     * @param feed archive to discard
     */
    public void discard(DownloadedStaticFeed feed) {
        discard(feed.file());
    }

    private long downloadInto(String feedId, String sourceUrl, Path target, MessageDigest digest)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofMillis(httpTimeouts.readMs()))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var statusCode = response.statusCode();

        if (statusCode < 200 || statusCode >= 300) {
            response.body().close();

            log.warn("Unexpected HTTP {} while downloading static feed {} from {}", statusCode, feedId, sourceUrl);
            throw new StaticFeedDownloadException("Unexpected HTTP " + statusCode + " while downloading static feed "
                    + feedId + " from " + sourceUrl);
        }

        try (InputStream in = new BufferedInputStream(response.body());
                var out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
            return in.transferTo(out);
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
     */
    public record DownloadedStaticFeed(Path file, String sha256, long sizeBytes) {}
}
