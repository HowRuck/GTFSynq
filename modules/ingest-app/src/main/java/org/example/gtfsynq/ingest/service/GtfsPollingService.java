package org.example.gtfsynq.ingest.service;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.inbound.protobuf.GtfsNativeFilter;
import org.example.gtfsynq.ingest.service.exception.GtfsIngestionException;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.shared.util.SizeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
@RequiredArgsConstructor
public class GtfsPollingService {

    private static final int MAX_ATTEMPTS = 2;
    private final RestClient restClient;
    private final GtfsNativeFilter nativeFilter;

    public byte[] downloadToBytes(String feedUrl) {
        var startTime = System.currentTimeMillis();
        log.info("Downloading GTFS feed from {}", feedUrl);

        try {
            var bytes = restClient.get().uri(feedUrl).retrieve().body(byte[].class);

            var safeBytes = Objects.requireNonNullElse(bytes, new byte[0]);

            log.info(
                    "Downloaded GTFS feed of size {} in {} ms",
                    SizeFormat.humanBytes(safeBytes.length),
                    System.currentTimeMillis() - startTime);

            return safeBytes;
        } catch (RestClientException e) {
            log.error("Network or HTTP error downloading feed from {}", feedUrl, e);
            return new byte[0];
        }
    }

    public List<BinaryFeedEntityWithMetadata> pollStream(String feedId, String feedUrl) {
        var startTime = System.currentTimeMillis();
        log.debug("Polling GTFS feed stream for feed {} from {}", feedId, feedUrl);

        try {
            return restClient.get().uri(feedUrl).exchange((_, response) -> {
                var statusCode = response.getStatusCode();

                if (statusCode == HttpStatus.TOO_MANY_REQUESTS) {
                    var retryAfter = response.getHeaders().getFirst("Retry-After");

                    log.warn(
                            "Rate limit hit for {}. Retry-After: {}s",
                            feedId,
                            Optional.ofNullable(retryAfter).orElse("unknown"));

                    return null;
                }

                if (!statusCode.is2xxSuccessful()) {
                    log.warn("Unexpected HTTP {} for {}", statusCode.value(), feedId);
                    return null;
                }

                try {
                    var feedBytes = response.getBody().readAllBytes();
                    return parseWithRetry(feedId, feedUrl, feedBytes);
                } catch (IOException e) {
                    throw new GtfsIngestionException("Failed to read response body", e);
                } finally {
                    log.debug("Processed {} in {} ms", feedId, System.currentTimeMillis() - startTime);
                }
            });
        } catch (RestClientException | GtfsIngestionException e) {
            log.error("Failed to poll feed {} due to: {}", feedId, e.getMessage());
            return null;
        }
    }

    private List<BinaryFeedEntityWithMetadata> parseWithRetry(String id, String url, byte[] data)
            throws InvalidProtocolBufferException {

        InvalidProtocolBufferException lastError = null;

        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (var is = new ByteArrayInputStream(data)) {
                return nativeFilter.parseNative(id, url, is);
            } catch (InvalidProtocolBufferException e) {
                lastError = e;
                log.warn("Parsing attempt {} failed for {}: {}", attempt, id, e.getMessage());
            } catch (IOException e) {
                throw new GtfsIngestionException("Stream closure error", e);
            }
        }

        throw Objects.requireNonNull(lastError, "Retry loop finished without capturing error");
    }
}
