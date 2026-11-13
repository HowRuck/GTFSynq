package org.example.gtfsynq.ingest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.gtfsynq.ingest.adapter.inbound.http.GtfsStaticFeedDownloader;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedDownload;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedRateLimitedException;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedValidators;
import org.example.gtfsynq.ingest.adapter.outbound.kafka.GtfsFeedPollKafkaProducer;
import org.example.gtfsynq.ingest.service.metrics.StaticFeedMetrics;
import org.example.gtfsynq.shared.protocol.FeedKind;
import org.example.gtfsynq.shared.protocol.PollStatus;
import org.junit.jupiter.api.Test;

class GtfsStaticIngestionAsyncServiceTest {

    private final RateLimitedDownloader downloader = new RateLimitedDownloader();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final List<PollStatus> published = new ArrayList<>();

    private final GtfsFeedPollKafkaProducer pollProducer = new GtfsFeedPollKafkaProducer(null, "polls") {
        @Override
        public void publish(
                FeedKind kind,
                String feedId,
                String sourceUrl,
                PollStatus status,
                long sizeBytes,
                String sha256,
                StaticFeedValidators validators) {
            published.add(status);
        }
    };

    private final GtfsStaticIngestionAsyncService service = new GtfsStaticIngestionAsyncService(
            downloader,
            null,
            null,
            pollProducer,
            new StaticFeedValidatorCache(),
            new StaticFeedMetrics(registry),
            null);

    @Test
    void skipsAFeedForTheRetryAfterDelay() {
        service.ingestOne("ovapi", "https://example.test/gtfs.zip");
        service.ingestOne("ovapi", "https://example.test/gtfs.zip");

        assertEquals(1, downloader.attempts.get(), "the second poll must not reach the origin again");
        assertEquals(List.of(PollStatus.POLL_STATUS_RATE_LIMITED), published, "only the real attempt is reported");
        assertEquals(
                2.0,
                registry.get("gtfs.static.ingest")
                        .tags("feed", "ovapi", "outcome", "rate_limited")
                        .counter()
                        .count(),
                "the limited attempt and the skipped poll are both visible");
    }

    /** Answers every download with a throttle, counting how often the origin was contacted. */
    private static final class RateLimitedDownloader extends GtfsStaticFeedDownloader {

        private final AtomicInteger attempts = new AtomicInteger();

        private RateLimitedDownloader() {
            super(null, null);
        }

        @Override
        public StaticFeedDownload download(String feedId, String sourceUrl, StaticFeedValidators validators) {
            attempts.incrementAndGet();
            throw new StaticFeedRateLimitedException("HTTP 429", Duration.ofMinutes(30));
        }
    }
}
