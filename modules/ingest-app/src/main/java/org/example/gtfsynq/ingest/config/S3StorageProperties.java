package org.example.gtfsynq.ingest.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the S3-compatible object storage that downloaded
 * GTFS static feed archives are written to.
 * <p>
 * Example configuration:
 *
 * <pre>
 * gtfsynq:
 *   storage:
 *     s3:
 *       endpoint: {@code http://localhost:9000}
 *       bucket: gtfsynq-static
 *       access-key: ...
 *       secret-key: ...
 * </pre>
 *
 * @param enabled whether static feed ingestion into object storage is enabled.
 *        When disabled, no S3 client is created and static feeds are not polled at all.
 *        Defaults to {@code true}.
 * @param endpoint endpoint of the S3-compatible service, e.g.
 *        {@code http://localhost:9000} for a local object store. Leave empty to use
 *        the standard AWS S3 endpoint.
 * @param region region to sign requests for. Defaults to {@code us-east-1}, which is
 *        also the region S3-compatible services use unless configured otherwise.
 * @param bucket bucket that holds the static feed archives. Must not be blank.
 * @param accessKey access key id. When either credential is blank, the AWS default
 *        credentials provider chain is used instead (environment, profile, container
 *        or instance role).
 * @param secretKey secret access key. See {@link #accessKey}.
 * @param pathStyleAccess whether to address buckets as {@code endpoint/bucket} instead of
 *        {@code bucket.endpoint}. Defaults to {@code true}, which is what local
 *        S3-compatible services need.
 */
@Validated
@ConfigurationProperties("gtfsynq.storage.s3")
@ImportRuntimeHints(GtfsPropertiesRuntimeHints.class)
public record S3StorageProperties(
        @DefaultValue("true") boolean enabled,

        String endpoint,

        @DefaultValue("us-east-1") String region,

        @NotBlank String bucket,

        String accessKey,

        String secretKey,

        @DefaultValue("true") boolean pathStyleAccess) {}
