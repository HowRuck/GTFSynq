package org.example.gtfsynq.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the S3-compatible object storage the store app reads GTFS static
 * feed archives from.
 * <p>
 * The keys intentionally mirror the ingest app's {@code gtfsynq.storage.s3} block so that
 * one set of environment variables configures both sides of the handoff. The bucket is
 * deliberately absent: an ingestion event names the bucket it was written to, so the
 * store app follows the event rather than pinning a location of its own.
 * <p>
 * Example configuration:
 *
 * <pre>
 * gtfsynq:
 *   storage:
 *     s3:
 *       endpoint: {@code http://localhost:9000}
 *       access-key: ...
 *       secret-key: ...
 * </pre>
 *
 * @param enabled whether static feed validation is enabled. When disabled, no S3 client is
 *        created and no ingestion event is consumed. Defaults to {@code true}.
 * @param endpoint endpoint of the S3-compatible service, e.g. {@code http://localhost:9000}.
 *        Leave empty to use the standard AWS S3 endpoint.
 * @param region region to sign requests for. Defaults to {@code us-east-1}.
 * @param accessKey access key id. When either credential is blank, the AWS default
 *        credentials provider chain is used instead.
 * @param secretKey secret access key. See {@link #accessKey}.
 * @param pathStyleAccess whether to address buckets as {@code endpoint/bucket} instead of
 *        {@code bucket.endpoint}. Defaults to {@code true}.
 */
@ConfigurationProperties("gtfsynq.storage.s3")
public record StaticFeedStorageProperties(
        @DefaultValue("true") boolean enabled,

        String endpoint,

        @DefaultValue("us-east-1") String region,

        String accessKey,

        String secretKey,

        @DefaultValue("true") boolean pathStyleAccess) {}
