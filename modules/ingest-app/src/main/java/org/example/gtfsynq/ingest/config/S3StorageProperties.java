package org.example.gtfsynq.ingest.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;

/**
 * Configuration properties for the S3-compatible object storage that downloaded
 * GTFS static feed archives are written to.
 * <p>
 * Example configuration:
 *
 * <pre>
 * gtfsynq.storage.s3.endpoint={@code http://localhost:9000}
 * gtfsynq.storage.s3.bucket=gtfsynq-static
 * gtfsynq.storage.s3.access-key=...
 * gtfsynq.storage.s3.secret-key=...
 * </pre>
 */
@ConfigMapping(prefix = "gtfsynq.storage.s3")
public interface S3StorageProperties {

    /**
     * Whether static feed ingestion into object storage is enabled. When disabled, no S3
     * client is created and static feeds are not polled at all. Defaults to {@code true}.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Endpoint of the S3-compatible service, e.g. {@code http://localhost:9000} for a local
     * object store. Empty (the default) uses the standard AWS S3 endpoint.
     */
    Optional<String> endpoint();

    /**
     * Region to sign requests for. Defaults to {@code us-east-1}, which is also the region
     * S3-compatible services use unless configured otherwise.
     */
    @WithDefault("us-east-1")
    String region();

    /**
     * Bucket that holds the static feed archives. Must not be blank.
     */
    @NotBlank
    @WithDefault("gtfsynq-static")
    String bucket();

    /**
     * Access key id. When empty (the default), the AWS default credentials provider chain is
     * used instead (environment, profile, container or instance role).
     */
    Optional<String> accessKey();

    /**
     * Secret access key. See {@link #accessKey()}.
     */
    Optional<String> secretKey();

    /**
     * Whether to address buckets as {@code endpoint/bucket} instead of
     * {@code bucket.endpoint}. Defaults to {@code true}, which is what local S3-compatible
     * services need.
     */
    @WithDefault("true")
    boolean pathStyleAccess();
}
