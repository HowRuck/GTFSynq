package org.example.gtfsynq.ingest.config;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires the S3 client used to store downloaded GTFS static feed archives.
 * <p>
 * The client is only created when {@code gtfsynq.storage.s3.enabled} is not set to
 * {@code false}; without it the static ingestion services are not registered either.
 * Building the client performs no network I/O, so an unreachable object store only
 * surfaces once a feed is actually ingested.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gtfsynq.storage.s3", name = "enabled", havingValue = "true", matchIfMissing = true)
public class S3StorageConfig {

    /**
     * Creates the S3 client. Credentials are taken from the configuration when both are
     * present, otherwise the default AWS credentials provider chain is used.
     *
     * @param properties S3 storage configuration
     * @return configured S3 client
     */
    @Bean
    public S3Client s3Client(S3StorageProperties properties) {
        var builder =
                S3Client.builder().region(Region.of(properties.region())).forcePathStyle(properties.pathStyleAccess());

        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }

        if (StringUtils.hasText(properties.accessKey()) && StringUtils.hasText(properties.secretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        }

        return builder.build();
    }
}
