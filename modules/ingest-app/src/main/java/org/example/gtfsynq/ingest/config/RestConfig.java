package org.example.gtfsynq.ingest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration class for setting up the RestClient bean with custom request factory
 */
@Configuration
public class RestConfig {

    /**
     * The read timeout in seconds for the RestClient request factory
     * <p>
     * Defaults to 120 seconds if not specified.
     */
    @Value("${rest.readTimeoutSeconds:120}")
    int readTimeoutSeconds;

    /**
     * The connect timeout in seconds for the RestClient request factory
     * <p>
     * Defaults to 20 seconds if not specified
     */
    @Value("${rest.connectTimeoutSeconds:40}")
    int connectTimeoutSeconds;

    /**
     * Configures and returns a {@link RestClient} bean with a custom request factory
     * that sets a connection timeout and read timeout based on the configured values
     *
     * @return a configured {@link RestClient} bean
     */
    @Bean
    public RestClient restClient() {
        var connectTimeoutMs = connectTimeoutSeconds * 1000;
        var readTimeoutMs = readTimeoutSeconds * 1000;

        var clientHttpRequestFactory = new HttpComponentsClientHttpRequestFactory();
        clientHttpRequestFactory.setConnectionRequestTimeout(connectTimeoutMs);
        clientHttpRequestFactory.setReadTimeout(readTimeoutMs);

        return RestClient.builder().requestFactory(clientHttpRequestFactory).build();
    }
}
