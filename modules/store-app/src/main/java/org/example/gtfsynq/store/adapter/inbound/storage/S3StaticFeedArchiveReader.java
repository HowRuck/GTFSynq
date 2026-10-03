package org.example.gtfsynq.store.adapter.inbound.storage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.shared.util.SizeFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Fetches GTFS static feed archives from object storage for validation.
 * <p>
 * The archive itself never travels over Kafka, so the ingestion event is only a pointer:
 * this resolves it into a local copy. Archives are an order of magnitude larger than
 * GTFS-RT payloads, so the object is streamed straight into a temporary file while its
 * SHA-256 digest is computed on the fly instead of being buffered in memory.
 * <p>
 * The digest is checked against the one the ingest app published. Archives are content
 * addressed by that digest, so a mismatch means the stored object is not the revision the
 * event refers to, and validating it would report on the wrong bytes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gtfsynq.storage.s3", name = "enabled", havingValue = "true", matchIfMissing = true)
public class S3StaticFeedArchiveReader {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final String TEMP_FILE_PREFIX = "gtfs-static-";

    private static final String TEMP_FILE_SUFFIX = ".zip";

    private final S3Client s3Client;

    /**
     * Downloads the archive referenced by an ingestion event to a temporary file.
     *
     * @param event ingestion event naming the bucket and object key
     * @return the staged archive; the caller owns the temporary file and must hand it back
     *     via {@link #discard(FetchedArchive)} when done with it
     * @throws StaticFeedFetchException when the object could not be read or its digest does
     *     not match the event
     */
    public FetchedArchive fetch(StaticFeedIngested event) {
        Path target = null;
        var handedOver = false;

        try {
            target = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);

            var digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            var sizeBytes = downloadInto(event, target, digest);

            if (sizeBytes <= 0) {
                throw new StaticFeedFetchException("Static feed " + event.getFeedId() + " stored at s3://"
                        + event.getBucket() + "/" + event.getObjectKey() + " was empty");
            }

            var sha256 = HexFormat.of().formatHex(digest.digest());
            verifyDigest(event, sha256);

            log.info(
                    "Fetched static feed {} from s3://{}/{} of size {} (sha256={})",
                    event.getFeedId(),
                    event.getBucket(),
                    event.getObjectKey(),
                    SizeFormat.humanBytes(sizeBytes),
                    sha256);

            handedOver = true;

            return new FetchedArchive(target, sha256, sizeBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", e);
        } catch (IOException e) {
            log.error(
                    "Failed to fetch static feed {} from s3://{}/{}",
                    event.getFeedId(),
                    event.getBucket(),
                    event.getObjectKey(),
                    e);
            throw new StaticFeedFetchException(
                    "Failed to fetch static feed " + event.getFeedId() + " from s3://" + event.getBucket() + "/"
                            + event.getObjectKey(),
                    e);
        } finally {
            if (!handedOver) {
                discard(target);
            }
        }
    }

    /**
     * Deletes the temporary file backing a fetched archive.
     *
     * @param archive archive to discard, may be {@code null} when the fetch failed
     */
    public void discard(FetchedArchive archive) {
        if (archive == null) {
            return;
        }

        discard(archive.file());
    }

    private long downloadInto(StaticFeedIngested event, Path target, MessageDigest digest) throws IOException {
        try (InputStream in = new BufferedInputStream(s3Client.getObject(
                        request -> request.bucket(event.getBucket()).key(event.getObjectKey()),
                        ResponseTransformer.toInputStream()));
                var out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
            return in.transferTo(out);
        }
    }

    private static void verifyDigest(StaticFeedIngested event, String actualSha256) {
        var expectedSha256 = event.getSha256();

        // Older events may predate the digest being populated; only a present
        // but wrong digest is a real inconsistency.
        if (expectedSha256.isEmpty() || expectedSha256.equals(actualSha256)) {
            return;
        }

        throw new StaticFeedFetchException("Stored archive for static feed " + event.getFeedId() + " has sha256="
                + actualSha256 + " but the ingestion event advertised sha256=" + expectedSha256);
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
     * A static feed archive staged in a temporary file
     *
     * @param file temporary file holding the archive
     * @param sha256 hex encoded SHA-256 digest of the archive
     * @param sizeBytes size of the archive in bytes
     */
    public record FetchedArchive(Path file, String sha256, long sizeBytes) {}
}
