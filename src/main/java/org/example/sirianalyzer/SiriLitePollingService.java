package org.example.sirianalyzer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Service
@Slf4j
public class SiriLitePollingService {

    private static final int MAX_IN_MEMORY_SIZE = 200 * 1024 * 1024; // 200 MB
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient webClient;
    private final String requestorId;

    public SiriLitePollingService(
        @Value("${entur.siriLite.baseUrl:https://api.entur.io}") String baseUrl,
        @Value("${entur.siriLite.requestorId:}") String configuredRequestorId
    ) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
            .build();

        HttpClient httpClient = HttpClient.create().compress(true); // enables gzip compression

        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .exchangeStrategies(strategies)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();

        this.requestorId = (configuredRequestorId != null &&
            !configuredRequestorId.isBlank())
            ? configuredRequestorId
            : UUID.randomUUID().toString();

        log.info(
            "SIRI Lite poller configured: baseUrl={}, requestorId={}",
            baseUrl,
            requestorId
        );
    }

    public String poll() {
        long startTime = System.currentTimeMillis();
        log.debug("Starting poll request to SIRI Lite API...");

        try {
            String result = webClient
                .get()
                .uri(uriBuilder ->
                    uriBuilder
                        .path("/realtime/v1/rest/et")
                        .queryParam("requestorId", requestorId)
                        .build()
                )
                .accept(
                    MediaType.APPLICATION_XML,
                    MediaType.APPLICATION_JSON,
                    MediaType.ALL
                )
                .retrieve()
                .onStatus(
                    status -> status.isError(),
                    response -> {
                        log.error("API Error: {}", response.statusCode());
                        return response
                            .bodyToMono(String.class)
                            .map(Exception::new);
                    }
                )
                .bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .doOnSuccess(body -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int sizeBytes = 0;
                    if (body != null) {
                        // compute actual byte size of the response using UTF-8
                        sizeBytes = body.getBytes(
                            StandardCharsets.UTF_8
                        ).length;
                    }
                    log.info(
                        "SIRI Lite API call completed in {} ms Response Size {}",
                        duration,
                        SizeFormat.humanBytes(sizeBytes)
                    );
                })
                .doOnError(e ->
                    log.error(
                        "Stream error after {} ms: ",
                        (System.currentTimeMillis() - startTime),
                        e
                    )
                )
                .block();

            return result != null ? result : "";
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Final catch block hit after {} ms: ", duration, e);
            return "";
        }
    }
}
