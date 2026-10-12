package org.example.gtfsynq.store.service;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.gtfsynq.shared.protocol.StaticFeedIngested;
import org.example.gtfsynq.shared.util.SizeFormat;
import org.example.gtfsynq.shared.validation.GtfsStaticArchiveValidator;
import org.example.gtfsynq.shared.validation.StaticValidationReport;
import org.example.gtfsynq.store.config.S3StorageProperties;
import org.example.gtfsynq.store.service.metrics.StaticValidationMetrics;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Validates a GTFS static archive referenced by an ingestion event.
 *
 * <p>The archive never travels over Kafka, so this fetches it from object storage, verifies the
 * SHA-256 digest carried by the event, and streams it through {@link GtfsStaticArchiveValidator}.
 * Validating here, before anything is persisted, is the point: rows reach the database only once
 * they are known good, and a revision that fails validation leaves the previous one in place.
 *
 * <p>The archive is streamed to a temporary file while its digest is computed, so peak memory does
 * not depend on archive size. Nothing is retained between events: a redelivered event is re-fetched
 * and re-validated, which is idempotent, if wasteful, which is why this is safe to acknowledge
 * after a failure.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
public class GtfsStaticFeedValidationService {

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final String TEMP_FILE_PREFIX = "gtfs-static-validation-";

    private static final String TEMP_FILE_SUFFIX = ".zip";

    private final S3Client s3Client;

    private final S3StorageProperties storageProperties;

    private final StaticValidationMetrics metrics;

    private final GtfsStaticArchiveValidator validator = new GtfsStaticArchiveValidator();

    /** What happened to an ingested archive, and so whether it may be persisted. */
    public enum Outcome {
        VALID,
        INVALID,
        CHECKSUM_MISMATCH,
        FETCH_FAILED,
        DISABLED
    }

    /** A fetched archive plus the digest computed while streaming it to disk. */
    private record StagedArchive(Path path, String sha256) {}

    /**
     * Validates the archive an ingestion event points at.
     *
     * @param event ingestion event carrying the bucket, key and digest of the stored archive
     * @return the outcome, which is the gate a persistence stage should key on
     */
    public Outcome validate(StaticFeedIngested event) {
        if (!storageProperties.enabled()) {
            log.debug("Static feed validation is disabled, skipping {}", event.getFeedId());
            metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.DISABLED);
            return Outcome.DISABLED;
        }

        var fetchStart = System.nanoTime();
        StagedArchive staged;
        try {
            staged = fetch(event);
        } catch (SdkException | IOException e) {
            metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.FETCH_FAILED);
            log.error(
                    "Static feed {} ({}) could not be staged from s3://{}/{}",
                    event.getFeedId(),
                    event.getSourceUrl(),
                    event.getBucket(),
                    event.getObjectKey(),
                    e);
            return Outcome.FETCH_FAILED;
        }
        metrics.recordFetch(System.nanoTime() - fetchStart);

        try {
            if (!staged.sha256().equalsIgnoreCase(event.getSha256())) {
                metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.CHECKSUM_MISMATCH);
                log.warn(
                        "Static feed {} ({}) does not match the digest recorded at ingestion "
                                + "(expected {}, got {}), refusing to validate",
                        event.getFeedId(),
                        event.getObjectKey(),
                        event.getSha256(),
                        staged.sha256());
                return Outcome.CHECKSUM_MISMATCH;
            }
            return validateArchive(event, staged.path());
        } finally {
            discard(staged.path());
        }
    }

    /** Streams the archive to a temporary file, the caller owning the file afterwards. */
    private StagedArchive fetch(StaticFeedIngested event) throws IOException {
        var target = Files.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
        var handedOver = false;
        try {
            var digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            var request = GetObjectRequest.builder()
                    .bucket(event.getBucket())
                    .key(event.getObjectKey())
                    .build();
            try (var sink = new DigestOutputStream(Files.newOutputStream(target), digest)) {
                s3Client.getObject(request, ResponseTransformer.toOutputStream(sink));
            }
            var staged = new StagedArchive(target, HexFormat.of().formatHex(digest.digest()));
            handedOver = true;
            return staged;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", e);
        } finally {
            if (!handedOver) {
                discard(target);
            }
        }
    }

    private Outcome validateArchive(StaticFeedIngested event, Path archive) {
        var start = System.nanoTime();
        StaticValidationReport report;
        try {
            report = validator.validate(archive);
        } catch (IOException e) {
            // Unreadable as a zip: a property of the feed, not a fault on our side, so this counts
            // as invalid rather than as an operational failure.
            metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.INVALID);
            log.error(
                    "Static feed {} ({}) could not be read as a zip archive, treating as invalid",
                    event.getFeedId(),
                    event.getObjectKey(),
                    e);
            return Outcome.INVALID;
        }
        metrics.recordValidation(event.getFeedId(), System.nanoTime() - start, report);

        if (report.valid()) {
            metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.VALID);
            log.info(
                    "Static feed {} ({}) of size {} is valid, {} rows checked",
                    event.getFeedId(),
                    event.getObjectKey(),
                    SizeFormat.humanBytes(event.getSizeBytes()),
                    report.rowsChecked());
            return Outcome.VALID;
        }

        metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.INVALID);
        log.warn(
                "Static feed {} ({}) is invalid: {} violations in {} rows checked{}",
                event.getFeedId(),
                event.getObjectKey(),
                report.violations(),
                report.rowsChecked(),
                incompleteNote(report));
        // Samples are capped per file by the validator, so replaying them here cannot flood logs.
        report.files()
                .forEach((file, outcome) -> outcome.samples()
                        .forEach(violation -> log.warn(
                                "  {}:{} {}: {}", file, violation.line(), violation.field(), violation.message())));
        return Outcome.INVALID;
    }

    /** Names the files whose checks did not run to completion, so a green report is not over-read. */
    private static String incompleteNote(StaticValidationReport report) {
        var incomplete = report.files().entrySet().stream()
                .filter(entry -> !entry.getValue().complete())
                .map(Map.Entry::getKey)
                .toList();
        return incomplete.isEmpty() ? "" : ", not fully checked: " + incomplete;
    }

    private void discard(Path archive) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException e) {
            log.warn("Could not delete temporary static archive {}", archive, e);
        }
    }
}
