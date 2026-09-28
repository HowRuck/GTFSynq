package org.example.gtfsynq.ingest.adapter.outbound.storage;

import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.config.S3StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Stores GTFS static feed archives in S3-compatible object storage.
 * <p>
 * Archives are content addressed: the object key contains the SHA-256 digest of the
 * archive, so ingesting the same revision twice is a no-op and every distinct revision
 * is kept side by side. Callers are expected to check {@link #exists(String)} before
 * uploading, which is what makes repeated polls of an unchanged feed cheap.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "gtfsynq.storage.s3", name = "enabled", havingValue = "true", matchIfMissing = true)
public class S3StaticFeedStorage {

    /**
     * Object key layout: {@code static/<feed id>/<sha256>.zip}
     */
    private static final String OBJECT_KEY_TEMPLATE = "static/%s/%s.zip";

    private static final String CONTENT_TYPE_ZIP = "application/zip";

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    private final AtomicBoolean bucketVerified = new AtomicBoolean(false);

    /**
     * Builds the object key an archive is stored under.
     *
     * @param feedId feed source identifier
     * @param sha256 hex encoded SHA-256 digest of the archive
     * @return object key
     */
    public String objectKey(String feedId, String sha256) {
        return OBJECT_KEY_TEMPLATE.formatted(feedId, sha256);
    }

    /**
     * @return the bucket archives are stored in
     */
    public String bucket() {
        return properties.bucket();
    }

    /**
     * Checks whether an archive is already stored.
     *
     * @param objectKey object key to look up
     * @return {@code true} when the object exists
     */
    public boolean exists(String objectKey) {
        verifyBucket();

        try {
            s3Client.headObject(request -> request.bucket(properties.bucket()).key(objectKey));
            return true;
        } catch (S3Exception e) {
            // HEAD responses carry no body, so S3-compatible services cannot always be
            // mapped onto the modelled NoSuchKeyException - fall back to the status code.
            if (e.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Uploads an archive.
     *
     * @param objectKey object key to store the archive under
     * @param file local archive file
     * @param metadata user metadata to store alongside the object
     * @return entity tag of the stored object
     */
    public String put(String objectKey, Path file, Map<String, String> metadata) {
        verifyBucket();

        var response = s3Client.putObject(
                request -> request.bucket(properties.bucket())
                        .key(objectKey)
                        .contentType(CONTENT_TYPE_ZIP)
                        .metadata(metadata),
                RequestBody.fromFile(file));

        return response.eTag();
    }

    /**
     * Makes sure the target bucket exists before the first request is issued to it.
     * Creating the bucket is what makes a fresh local object store usable without any
     * manual setup step.
     */
    private synchronized void verifyBucket() {
        if (bucketVerified.get()) {
            return;
        }

        var bucket = properties.bucket();

        try {
            s3Client.headBucket(request -> request.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() != HttpURLConnection.HTTP_NOT_FOUND) {
                throw e;
            }

            log.info("Bucket {} does not exist yet, creating it", bucket);

            try {
                s3Client.createBucket(request -> request.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException alreadyExists) {
                log.debug("Bucket {} was created concurrently", bucket);
            }
        }

        bucketVerified.set(true);
    }
}
