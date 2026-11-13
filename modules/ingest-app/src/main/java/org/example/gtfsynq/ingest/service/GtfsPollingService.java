package org.example.gtfsynq.ingest.service;

import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedValidators;
import org.example.gtfsynq.ingest.adapter.inbound.protobuf.GtfsNativeFilter;
import org.example.gtfsynq.ingest.adapter.outbound.kafka.GtfsFeedPollKafkaProducer;
import org.example.gtfsynq.ingest.config.HttpTimeouts;
import org.example.gtfsynq.ingest.service.exception.GtfsIngestionException;
import org.example.gtfsynq.shared.protocol.BinaryFeedEntityWithMetadata;
import org.example.gtfsynq.shared.protocol.FeedKind;
import org.example.gtfsynq.shared.protocol.PollStatus;
import org.example.gtfsynq.shared.util.SizeFormat;

@ApplicationScoped
@Slf4j
@RequiredArgsConstructor
public class GtfsPollingService {

    private static final int MAX_ATTEMPTS = 2;
    private final HttpClient httpClient;
    private final HttpTimeouts httpTimeouts;
    private final GtfsNativeFilter nativeFilter;
    private final GtfsFeedPollKafkaProducer feedPollProducer;

    /**
     * ETag / Last-Modified of the last successfully parsed response per feed. Kept in memory only:
     * after a restart the first poll is unconditional.
     */
    private final ConcurrentMap<String, StaticFeedValidators> validators = new ConcurrentHashMap<>();

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
            log.error("I/O error downloading feed from {}", feedUrl, e);
            return new byte[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while downloading feed from {}", feedUrl, e);
            return new byte[0];
        }
    }

    @WithSpan
    public List<BinaryFeedEntityWithMetadata> pollStream(String feedId, String feedUrl) {
        var startTime = System.currentTimeMillis();
        log.debug("Polling GTFS feed stream for feed {} from {}", feedId, feedUrl);

        try {
            var validatorKey = feedId + "\0" + feedUrl;
            var known = validators.getOrDefault(validatorKey, StaticFeedValidators.none());
            var response = httpClient.send(request(feedUrl, known), HttpResponse.BodyHandlers.ofByteArray());
            var statusCode = response.statusCode();

            if (statusCode == 304) {
                log.info("Feed {} not modified (etag={}, lastModified={})", feedId, known.etag(), known.lastModified());
                publishState(feedId, feedUrl, PollStatus.POLL_STATUS_NOT_MODIFIED, null, known);
                return List.of();
            }

            if (statusCode == 429) {
                var retryAfter = response.headers().firstValue("Retry-After").orElse(null);

                log.warn(
                        "Rate limit hit for {}. Retry-After: {}s",
                        feedId,
                        Optional.ofNullable(retryAfter).orElse("unknown"));

                publishState(feedId, feedUrl, PollStatus.POLL_STATUS_RATE_LIMITED, null, null);
                return null;
            }

            if (statusCode < 200 || statusCode >= 300) {
                log.warn("Unexpected HTTP {} for {}", statusCode, feedId);
                publishState(feedId, feedUrl, PollStatus.POLL_STATUS_HTTP_ERROR, null, null);
                return null;
            }

            try {
                var feedBytes = response.body();
                var entities = parseWithRetry(feedId, feedUrl, feedBytes);
                // Remember validators only after a successful parse so a failure is retried in full.
                var received = new StaticFeedValidators(
                        response.headers().firstValue("ETag").orElse(null),
                        response.headers().firstValue("Last-Modified").orElse(null));
                if (received.isEmpty()) {
                    validators.remove(validatorKey);
                } else {
                    validators.put(validatorKey, received);
                }
                publishState(feedId, feedUrl, PollStatus.POLL_STATUS_OK, feedBytes, received);
                return entities;
            } finally {
                log.debug("Processed {} in {} ms", feedId, System.currentTimeMillis() - startTime);
            }
        } catch (IOException e) {
            log.error("I/O error polling feed {}: {}", feedId, e.getMessage());
            publishState(feedId, feedUrl, PollStatus.POLL_STATUS_ERROR, null, null);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while polling feed {}: {}", feedId, e.getMessage());
            return null;
        } catch (GtfsIngestionException e) {
            log.error("Ingestion error polling feed {}: {}", feedId, e.getMessage());
            publishState(feedId, feedUrl, PollStatus.POLL_STATUS_ERROR, null, null);
            return null;
        } catch (RuntimeException e) {
            log.error("Unexpected error polling feed {}: {}", feedId, e.getMessage(), e);
            publishState(feedId, feedUrl, PollStatus.POLL_STATUS_ERROR, null, null);
            return null;
        }
    }

    private void publishState(
            String feedId, String feedUrl, PollStatus status, byte[] body, StaticFeedValidators validators) {
        var sha256 =
                body == null ? null : HexFormat.of().formatHex(sha256Digest().digest(body));
        feedPollProducer.publish(
                FeedKind.FEED_KIND_REALTIME,
                feedId,
                feedUrl,
                status,
                body == null ? 0 : body.length,
                sha256,
                validators);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private HttpRequest request(String feedUrl) {
        return request(feedUrl, StaticFeedValidators.none());
    }

    private HttpRequest request(String feedUrl, StaticFeedValidators known) {
        var builder = HttpRequest.newBuilder(URI.create(feedUrl))
                .timeout(Duration.ofMillis(httpTimeouts.readMs()))
                .GET();
        if (known.etag() != null) {
            builder.header("If-None-Match", known.etag());
        }
        if (known.lastModified() != null) {
            builder.header("If-Modified-Since", known.lastModified());
        }
        return builder.build();
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
