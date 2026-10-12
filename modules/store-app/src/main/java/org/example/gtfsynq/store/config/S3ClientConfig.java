package org.example.gtfsynq.store.config;

import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Wires the S3 client used to fetch stored GTFS static archives for validation.
 *
 * <p>The builder is provided by the Quarkus Amazon S3 extension, which also supplies the GraalVM
 * substitutions that make the AWS SDK native-image friendly. This producer applies the
 * application's own region, endpoint and credentials on top of it. Path-style access is left to the
 * extension's {@code quarkus.s3.path-style-access} setting, which it applies at the S3 service
 * level; setting it here as well would make the SDK reject the client.
 *
 * <p>The client is always created; whether archives are actually read is decided at runtime from
 * {@link S3StorageProperties#enabled()}. Building the client performs no network I/O.
 */
@Singleton
@RequiredArgsConstructor
public class S3ClientConfig {

    private final S3StorageProperties properties;

    /**
     * Creates the S3 client. Credentials are taken from the configuration when both are present,
     * otherwise the default AWS credentials provider chain is used.
     *
     * @return configured S3 client
     */
    @Produces
    @Singleton
    public S3Client s3Client(S3ClientBuilder builder) {
        builder.region(Region.of(properties.region()));

        properties
                .endpoint()
                .filter(endpoint -> !endpoint.isBlank())
                .ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));

        var accessKey = properties.accessKey().filter(key -> !key.isBlank());
        var secretKey = properties.secretKey().filter(key -> !key.isBlank());

        if (accessKey.isPresent() && secretKey.isPresent()) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.get(), secretKey.get())));
        }

        return builder.build();
    }

    void dispose(@Disposes S3Client client) {
        client.close();
    }
}
