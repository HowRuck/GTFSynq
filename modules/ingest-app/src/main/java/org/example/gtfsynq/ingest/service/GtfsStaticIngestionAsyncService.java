package org.example.gtfsynq.ingest.service;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.inbound.http.GtfsStaticFeedDownloader;
import org.example.gtfsynq.ingest.adapter.inbound.http.GtfsStaticFeedDownloader.DownloadedStaticFeed;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedDownloadException;
import org.example.gtfsynq.ingest.adapter.outbound.kafka.GtfsStaticFeedKafkaProducer;
import org.example.gtfsynq.ingest.adapter.outbound.storage.S3StaticFeedStorage;
import org.example.gtfsynq.ingest.config.S3StorageProperties;
import org.example.gtfsynq.ingest.service.metrics.StaticFeedMetrics;
import org.example.gtfsynq.ingest.service.metrics.StaticFeedMetrics.Outcome;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.shared.util.SizeFormat;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Runs the static feed ingestion pipeline for a single source: download the archive,
 * store it in object storage and publish an ingestion event
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticIngestionAsyncService {

    private final GtfsStaticFeedDownloader staticFeedDownloader;
    private final S3StaticFeedStorage staticFeedStorage;
    private final GtfsStaticFeedKafkaProducer staticFeedKafkaProducer;
    private final StaticFeedMetrics metrics;
    private final S3StorageProperties storageProperties;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Ingests one static feed source.
     * <p>
     * A feed whose content is already stored is skipped entirely: no upload and no
     * event, so downstream consumers are only woken up by an actual revision change.
     *
     * @param feedId feed source identifier
     * @param sourceUrl URL of the static feed archive
     * @return a future that completes when the source was processed or failed
     */
    public CompletableFuture<Void> ingestAsync(String feedId, String sourceUrl) {
        if (!storageProperties.enabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(
                () -> {
                    var downloadStart = System.nanoTime();

                    try {
                        var feed = staticFeedDownloader.download(feedId, sourceUrl);
                        metrics.recordDownload(feedId, System.nanoTime() - downloadStart, feed.sizeBytes());

                        try {
                            storeAndPublish(feedId, sourceUrl, feed);
                        } finally {
                            staticFeedDownloader.discard(feed);
                        }
                    } catch (StaticFeedDownloadException e) {
                        metrics.recordDownloadFailure(feedId, System.nanoTime() - downloadStart);
                        metrics.recordOutcome(feedId, Outcome.FAILED);
                        log.error("Static feed {} ({}) could not be downloaded", feedId, sourceUrl, e);
                    } catch (Exception e) {
                        metrics.recordOutcome(feedId, Outcome.FAILED);
                        log.error("Unexpected error ingesting static feed {} ({})", feedId, sourceUrl, e);
                    }
                },
                executor);
    }

    void shutdown(@Observes ShutdownEvent event) {
        executor.shutdown();
    }

    private void storeAndPublish(String feedId, String sourceUrl, DownloadedStaticFeed feed) {
        var objectKey = staticFeedStorage.objectKey(feedId, feed.sha256());

        try {
            if (staticFeedStorage.exists(objectKey)) {
                metrics.recordOutcome(feedId, Outcome.UNCHANGED);
                log.info("Static feed {} is unchanged (sha256={}), skipping storage and event", feedId, feed.sha256());
                return;
            }

            var etag = staticFeedStorage.put(objectKey, feed.file(), Map.of("sha256", feed.sha256()));

            staticFeedKafkaProducer.send(StaticFeedIngested.newBuilder()
                    .setFeedId(feedId)
                    .setBucket(staticFeedStorage.bucket())
                    .setObjectKey(objectKey)
                    .setSourceUrl(sourceUrl)
                    .setSizeBytes(feed.sizeBytes())
                    .setSha256(feed.sha256())
                    .setIngestedAtEpochSeconds(Instant.now().getEpochSecond())
                    .setEtag(Objects.requireNonNullElse(etag, ""))
                    .build());

            metrics.recordOutcome(feedId, Outcome.SUCCESS);

            log.info(
                    "Stored static feed {} as s3://{}/{} of size {}",
                    feedId,
                    staticFeedStorage.bucket(),
                    objectKey,
                    SizeFormat.humanBytes(feed.sizeBytes()));
        } catch (SdkException e) {
            metrics.recordOutcome(feedId, Outcome.FAILED);
            log.error("Failed to store static feed {} from {}", feedId, sourceUrl, e);
        }
    }
}
