package org.example.gtfsynq.ingest.config;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configuration class for setting up the {@link HttpClient} bean used for feed downloads.
 */
@Singleton
public class HttpClientConfig {

    private final GtfsProperties gtfsProperties;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;

    @Inject
    public HttpClientConfig(
            GtfsProperties gtfsProperties,
            @ConfigProperty(name = "gtfsynq.http.connect-timeout-seconds", defaultValue = "40")
                    int connectTimeoutSeconds,
            @ConfigProperty(name = "gtfsynq.http.read-timeout-seconds", defaultValue = "120") int readTimeoutSeconds) {
        this.gtfsProperties = gtfsProperties;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    /**
     * Configures and returns a {@link HttpClient} bean whose connect timeout is the lower of
     * the configured connect timeout and the realtime feed timeout. Redirects are followed
     * exactly as the previous Apache-based client did for GET requests.
     *
     * @return a configured {@link HttpClient} bean
     */
    @Produces
    @Singleton
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeouts().connectMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Exposes the effective connect/read timeouts so callers can apply them per request.
     *
     * @return the capped request timeouts
     */
    @Produces
    @Singleton
    public HttpTimeouts httpTimeouts() {
        return timeouts();
    }

    private HttpTimeouts timeouts() {
        var feedTimeoutMs = gtfsProperties.feedTimeoutSeconds() * 1000L;

        var connectTimeoutMs = Math.min(connectTimeoutSeconds * 1000L, feedTimeoutMs);
        var readTimeoutMs = Math.min(readTimeoutSeconds * 1000L, feedTimeoutMs);

        return new HttpTimeouts(connectTimeoutMs, readTimeoutMs);
    }
}
