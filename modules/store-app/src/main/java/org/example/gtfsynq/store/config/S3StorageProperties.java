package org.example.gtfsynq.store.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Location of the object storage holding GTFS static archives, read by the store app to fetch an
 * archive for validation.
 *
 * <p>Mirrors the ingest app's {@code gtfsynq.storage.s3} contract under the same prefix, so one set
 * of deployment values serves both apps. It is declared per app rather than shared because a
 * {@code @ConfigMapping} in a library module is not part of the application's CDI index and would
 * not be discovered; only the read-relevant settings are declared here.
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
     * Whether static archives are read and validated. When disabled the consumer acknowledges
     * without fetching anything, mirroring the ingest side's master switch. Defaults to
     * {@code true}.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Endpoint of the S3-compatible service, e.g. {@code http://localhost:9000} for a local object
     * store. Empty (the default) uses the standard AWS S3 endpoint.
     */
    Optional<String> endpoint();

    /**
     * Region to sign requests for. Defaults to {@code us-east-1}, which is also the region
     * S3-compatible services use unless configured otherwise.
     */
    @WithDefault("us-east-1")
    String region();

    /**
     * Bucket holding the static feed archives. Only used as a fallback: the ingestion event carries
     * the bucket the archive was actually written to.
     */
    @WithDefault("gtfsynq-static")
    String bucket();

    /**
     * Access key id. When empty (the default), the AWS default credentials provider chain is used
     * instead (environment, profile, container or instance role).
     */
    Optional<String> accessKey();

    /** Secret access key. See {@link #accessKey()}. */
    Optional<String> secretKey();
}
