package org.example.gtfsynq.ingest.service;

import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.inbound.protobuf.GtfsNativeFilter;
import org.example.gtfsynq.ingest.config.HttpTimeouts;
import org.example.gtfsynq.ingest.service.exception.GtfsIngestionException;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.shared.util.SizeFormat;

@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsPollingService {

    private static final int MAX_ATTEMPTS = 2;
    private final HttpClient httpClient;
    private final HttpTimeouts httpTimeouts;
    private final GtfsNativeFilter nativeFilter;

    public byte[] downloadToBytes(String feedUrl) {
        var startTime = System.currentTimeMillis();
        log.info("Downloading GTFS feed from {}", feedUrl);

        try {
            var response = httpClient.send(request(feedUrl), HttpResponse.BodyHandlers.ofByteArray());

            var bytes = response.body();

            var safeBytes = Objects.requireNonNullElse(bytes, new byte[0]);

            log.info(
                    "Downloaded GTFS feed of size {} in {} ms",
                    SizeFormat.humanBytes(safeBytes.length),
                    System.currentTimeMillis() - startTime);

            return safeBytes;
        } catch (IOException e) {
            log.error("Network or HTTP error downloading feed from {}", feedUrl, e);
            return new byte[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Network or HTTP error downloading feed from {}", feedUrl, e);
            return new byte[0];
        }
    }

    public List<BinaryFeedEntityWithMetadata> pollStream(String feedId, String feedUrl) {
        var startTime = System.currentTimeMillis();
        log.debug("Polling GTFS feed stream for feed {} from {}", feedId, feedUrl);

        try {
            var response = httpClient.send(request(feedUrl), HttpResponse.BodyHandlers.ofByteArray());
            var statusCode = response.statusCode();

            if (statusCode == 429) {
                var retryAfter = response.headers().firstValue("Retry-After").orElse(null);

                log.warn(
                        "Rate limit hit for {}. Retry-After: {}s",
                        feedId,
                        Optional.ofNullable(retryAfter).orElse("unknown"));

                return null;
            }

            if (statusCode < 200 || statusCode >= 300) {
                log.warn("Unexpected HTTP {} for {}", statusCode, feedId);
                return null;
            }

            try {
                var feedBytes = response.body();
                return parseWithRetry(feedId, feedUrl, feedBytes);
            } finally {
                log.debug("Processed {} in {} ms", feedId, System.currentTimeMillis() - startTime);
            }
        } catch (IOException e) {
            log.error("Failed to poll feed {} due to: {}", feedId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to poll feed {} due to: {}", feedId, e.getMessage());
            return null;
        } catch (GtfsIngestionException e) {
            log.error("Failed to poll feed {} due to: {}", feedId, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.error("Unexpected error polling feed {}: {}", feedId, e.getMessage(), e);
            return null;
        }
    }

    private HttpRequest request(String feedUrl) {
        return HttpRequest.newBuilder(URI.create(feedUrl))
                .timeout(Duration.ofMillis(httpTimeouts.readMs()))
                .GET()
                .build();
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
