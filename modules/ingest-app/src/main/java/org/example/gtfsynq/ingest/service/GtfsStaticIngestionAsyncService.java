package org.example.gtfsynq.ingest.service;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.ingest.adapter.inbound.http.GtfsStaticFeedDownloader;
import org.example.gtfsynq.ingest.adapter.inbound.http.GtfsStaticFeedDownloader.DownloadedStaticFeed;
import org.example.gtfsynq.ingest.adapter.inbound.http.StaticFeedDownload;
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
 * Runs the static feed ingestion pipeline for a single source: revalidate the archive against
 * the HTTP validators of the previous poll, store it in object storage and publish an ingestion
 * event when it changed
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticIngestionAsyncService {

    private final GtfsStaticFeedDownloader staticFeedDownloader;
    private final S3StaticFeedStorage staticFeedStorage;
    private final GtfsStaticFeedKafkaProducer staticFeedKafkaProducer;
    private final StaticFeedValidatorCache validatorCache;
    private final StaticFeedMetrics metrics;
    private final S3StorageProperties storageProperties;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Ingests one static feed source.
     * <p>
     * A feed the origin reports as not modified is skipped entirely, and so is a feed whose
     * content is already stored: no upload and no event, so downstream consumers are only woken
     * up by an actual revision change.
     *
     * @param feedId feed source identifier
     * @param sourceUrl URL of the static feed archive
     * @return a future that completes when the source was processed or failed
     */
    public CompletableFuture<Void> ingestAsync(String feedId, String sourceUrl) {
        if (!storageProperties.enabled()) {
            return CompletableFuture.completedFuture(null);
        }

        // Carry the caller's span (the static poll cycle) onto the virtual thread.
        var context = Context.current();
        return CompletableFuture.runAsync(context.wrap(() -> ingestOne(feedId, sourceUrl)), executor);
    }

    @WithSpan
    void ingestOne(String feedId, String sourceUrl) {
        var knownValidators = validatorCache.get(feedId, sourceUrl);
        var downloadStart = System.nanoTime();

        try {
            var download = staticFeedDownloader.download(feedId, sourceUrl, knownValidators);

            switch (download) {
                case StaticFeedDownload.NotModified notModified -> {
                    validatorCache.put(feedId, sourceUrl, notModified.validators());
                    metrics.recordNotModified(feedId);
                    metrics.recordOutcome(feedId, Outcome.UNCHANGED);

                    log.info(
                            "Static feed {} is unchanged since the last poll (etag={}, lastModified={}), "
                                    + "skipping download, storage and event",
                            feedId,
                            notModified.validators().etag(),
                            notModified.validators().lastModified());
                }
                case StaticFeedDownload.Downloaded downloaded ->
                    handleDownloaded(feedId, sourceUrl, downloaded.feed(), System.nanoTime() - downloadStart);
            }
        } catch (StaticFeedDownloadException e) {
            metrics.recordDownloadFailure(feedId, System.nanoTime() - downloadStart);
            metrics.recordOutcome(feedId, Outcome.FAILED);
            log.error("Static feed {} ({}) could not be downloaded", feedId, sourceUrl, e);
        } catch (Exception e) {
            metrics.recordOutcome(feedId, Outcome.FAILED);
            log.error("Unexpected error ingesting static feed {} ({})", feedId, sourceUrl, e);
        }
    }

    private void handleDownloaded(String feedId, String sourceUrl, DownloadedStaticFeed feed, long downloadNanos) {
        metrics.recordDownload(feedId, downloadNanos, feed.sizeBytes());

        try {
            if (storeAndPublish(feedId, sourceUrl, feed)) {
                // Only a revision that actually reached object storage may be revalidated by
                // the next poll; remembering a failed attempt would let a 304 hide the retry.
                validatorCache.put(feedId, sourceUrl, feed.validators());
            }
        } finally {
            staticFeedDownloader.discard(feed);
        }
    }

    void shutdown(@Observes ShutdownEvent event) {
        executor.shutdown();
    }

    private boolean storeAndPublish(String feedId, String sourceUrl, DownloadedStaticFeed feed) {
        var objectKey = staticFeedStorage.objectKey(feedId, feed.sha256());

        try {
            if (staticFeedStorage.exists(objectKey)) {
                metrics.recordOutcome(feedId, Outcome.UNCHANGED);
                log.info("Static feed {} is unchanged (sha256={}), skipping storage and event", feedId, feed.sha256());
                return true;
            }

            var metadata = new LinkedHashMap<String, String>();
            metadata.put("sha256", feed.sha256());
            putIfPresent(metadata, "source-etag", feed.validators().etag());
            putIfPresent(metadata, "source-last-modified", feed.validators().lastModified());

            var etag = staticFeedStorage.put(objectKey, feed.file(), metadata);

            staticFeedKafkaProducer.send(StaticFeedIngested.newBuilder()
                    .setFeedId(feedId)
                    .setBucket(staticFeedStorage.bucket())
                    .setObjectKey(objectKey)
                    .setSourceUrl(sourceUrl)
                    .setSizeBytes(feed.sizeBytes())
                    .setSha256(feed.sha256())
                    .setIngestedAtEpochSeconds(Instant.now().getEpochSecond())
                    .setEtag(Objects.requireNonNullElse(etag, ""))
                    .setSourceEtag(Objects.requireNonNullElse(feed.validators().etag(), ""))
                    .setSourceLastModified(
                            Objects.requireNonNullElse(feed.validators().lastModified(), ""))
                    .build());

            metrics.recordOutcome(feedId, Outcome.SUCCESS);

            log.info(
                    "Stored static feed {} as s3://{}/{} of size {}",
                    feedId,
                    staticFeedStorage.bucket(),
                    objectKey,
                    SizeFormat.humanBytes(feed.sizeBytes()));

            return true;
        } catch (SdkException e) {
            metrics.recordOutcome(feedId, Outcome.FAILED);
            log.error("Failed to store static feed {} from {}", feedId, sourceUrl, e);
            return false;
        }
    }

    private static void putIfPresent(LinkedHashMap<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
