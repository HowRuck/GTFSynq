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
 * Stages and validates a GTFS static archive referenced by an ingestion event.
 *
 * <p>The archive never travels over Kafka, so this fetches it from object storage, verifies the
 * SHA-256 digest carried by the event, and streams it through {@link GtfsStaticArchiveValidator}.
 * Validating here, before anything is persisted, is the point: rows reach the database only once
 * they are known good, and a revision that fails validation leaves the previous one in place.
 *
 * <p>The archive is streamed to a temporary file while its digest is computed, so peak memory does
 * not depend on archive size. Staging and validating are one call and the staged file is handed
 * back rather than deleted here, because the stage that follows - persistence - needs the very same
 * bytes; the caller owns the file and releases it with {@link #discard}. Nothing is retained
 * between events: a redelivered event is re-fetched and re-validated, which is idempotent, if
 * wasteful, which is why this is safe to acknowledge after a failure.
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

    /**
     * Outcome of {@link #stage}, with the staged archive when there is one.
     *
     * @param outcome whether the archive may be persisted
     * @param archive staged file, {@code null} unless the archive was fetched and validated
     * @param report per-file validation detail, {@code null} unless the archive was read
     */
    public record Validation(Outcome outcome, Path archive, StaticValidationReport report) {

        /** Whether the archive behind this result may be handed on for persistence. */
        public boolean valid() {
            return outcome == Outcome.VALID;
        }
    }

    /** A fetched archive plus the digest computed while streaming it to disk. */
    private record StagedArchive(Path path, String sha256) {}

    /**
     * Stages the archive an ingestion event points at and validates it.
     *
     * <p>The caller owns the returned archive and must release it with {@link #discard}; anything
     * but {@link Outcome#VALID} hands back no file, because an archive that was rejected must not
     * reach the database.
     *
     * @param event ingestion event carrying the bucket, key and digest of the stored archive
     * @return the outcome, which is the gate the persistence stage keys on
     */
    public Validation stage(StaticFeedIngested event) {
        if (!storageProperties.enabled()) {
            log.debug("Static feed validation is disabled, skipping {}", event.getFeedId());
            metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.DISABLED);
            return new Validation(Outcome.DISABLED, null, null);
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
            return new Validation(Outcome.FETCH_FAILED, null, null);
        }
        metrics.recordFetch(System.nanoTime() - fetchStart);

        if (!staged.sha256().equalsIgnoreCase(event.getSha256())) {
            metrics.recordResult(event.getFeedId(), StaticValidationMetrics.Result.CHECKSUM_MISMATCH);
            log.warn(
                    "Static feed {} ({}) does not match the digest recorded at ingestion "
                            + "(expected {}, got {}), refusing to validate",
                    event.getFeedId(),
                    event.getObjectKey(),
                    event.getSha256(),
                    staged.sha256());
            discard(staged.path());
            return new Validation(Outcome.CHECKSUM_MISMATCH, null, null);
        }
        return validateArchive(event, staged.path());
    }

    /** Releases a staged archive; a {@code null} path is accepted so callers need no null check. */
    public void discard(Path archive) {
        if (archive == null) {
            return;
        }
        try {
            Files.deleteIfExists(archive);
        } catch (IOException e) {
            log.warn("Could not delete temporary static archive {}", archive, e);
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

    private Validation validateArchive(StaticFeedIngested event, Path archive) {
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
            discard(archive);
            return new Validation(Outcome.INVALID, null, null);
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
            return new Validation(Outcome.VALID, archive, report);
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
        // An archive that failed validation must not reach the database, so the staged copy goes now.
        discard(archive);
        return new Validation(Outcome.INVALID, null, report);
    }

    /** Names the files whose checks did not run to completion, so a green report is not over-read. */
    private static String incompleteNote(StaticValidationReport report) {
        var incomplete = report.files().entrySet().stream()
                .filter(entry -> !entry.getValue().complete())
                .map(Map.Entry::getKey)
                .toList();
        return incomplete.isEmpty() ? "" : ", not fully checked: " + incomplete;
    }
}
