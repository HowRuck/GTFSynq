package org.example.gtfsynq.store.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.example.gtfsynq.store.adapter.inbound.storage.S3StaticFeedArchiveReader.FetchedArchive;
import org.example.gtfsynq.store.config.StaticFeedValidationConfig;
import org.mobilitydata.gtfsvalidator.input.CountryCode;
import org.mobilitydata.gtfsvalidator.input.DateForValidation;
import org.mobilitydata.gtfsvalidator.input.GtfsInput;
import org.mobilitydata.gtfsvalidator.notice.NoticeContainer;
import org.mobilitydata.gtfsvalidator.notice.ResolvedNotice;
import org.mobilitydata.gtfsvalidator.notice.SeverityLevel;
import org.mobilitydata.gtfsvalidator.runner.ValidationRunner;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedLoader;
import org.mobilitydata.gtfsvalidator.util.ServiceIntervalCache;
import org.mobilitydata.gtfsvalidator.validator.ClassGraphDiscovery;
import org.mobilitydata.gtfsvalidator.validator.ValidationContext;
import org.mobilitydata.gtfsvalidator.validator.ValidatorLoader;
import org.mobilitydata.gtfsvalidator.validator.ValidatorLoaderException;
import org.springframework.stereotype.Service;

/**
 * Validates GTFS static feed archives with MobilityData's canonical GTFS Schedule
 * validator.
 * <p>
 * This deliberately drives the validator's loading and validation stages directly rather
 * than through its {@link ValidationRunner}: the runner owns report rendering and a
 * version check that reaches out over the network, neither of which is wanted here. The
 * notices it collects are the actual result, so writing report files is pure overhead
 * for a consumer that only needs to know whether a feed is usable.
 * <p>
 * Rules and table descriptors are discovered from the classpath on each call. Both scans
 * are cached by the validator's own libraries, and static feeds arrive at most daily per
 * feed, so the cost is irrelevant next to validating the feed itself.
 */
@Service
@RequiredArgsConstructor
public class GtfsStaticFeedValidator {

    private final StaticFeedValidationConfig config;

    /**
     * Validates a fetched static feed archive.
     *
     * @param feedId feed source identifier, used for reporting
     * @param archive the staged archive to validate
     * @return the notice counts and the pass/fail verdict against the configured tolerance
     * @throws GtfsStaticFeedValidationException when the validator itself could not be run,
     *     as opposed to finding problems in the feed
     */
    public StaticFeedValidationResult validate(String feedId, FetchedArchive archive) {
        var startedAt = System.nanoTime();

        var notices = new NoticeContainer();

        var feedLoader = new GtfsFeedLoader(ClassGraphDiscovery.discoverTables());
        feedLoader.setNumThreads(config.numThreads());

        var validators = loadValidators();

        GtfsInput input;
        try {
            input = GtfsInput.createFromPath(archive.file(), notices);
        } catch (IOException e) {
            throw new GtfsStaticFeedValidationException(
                    "Could not open static feed archive " + archive.file() + " for feed " + feedId, e);
        }

        try {
            ValidationRunner.loadAndValidate(validators, feedLoader, notices, input, validationContext());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GtfsStaticFeedValidationException("Validation of static feed " + feedId + " was interrupted", e);
        } finally {
            // Closing can add IO errors to the container, so it has to happen before
            // the notice counts are read below.
            ValidationRunner.closeGtfsInput(input, notices);
        }

        return summarise(feedId, archive.sha256(), notices, System.nanoTime() - startedAt);
    }

    private static ValidatorLoader loadValidators() {
        try {
            return ValidatorLoader.createForClasses(ClassGraphDiscovery.discoverValidatorsInDefaultPackage());
        } catch (ValidatorLoaderException e) {
            throw new GtfsStaticFeedValidationException("Could not load the GTFS validator rule set", e);
        }
    }

    private ValidationContext validationContext() {
        return ValidationContext.builder()
                .setCountryCode(CountryCode.forStringOrUnknown(config.countryCode()))
                .set(ServiceIntervalCache.class, new ServiceIntervalCache())
                .setDateForValidation(new DateForValidation(LocalDate.now()))
                .build();
    }

    private StaticFeedValidationResult summarise(
            String feedId, String sha256, NoticeContainer notices, long durationNanos) {
        // Resolved notices are the deduplicated, severity-tagged view the validator's own
        // reports are built from, so counting those matches what upstream would report.
        // ResolvedNotice is generic in its notice type; the concrete notice type is not
        // needed here, only the severity it carries.
        var resolved = notices.getResolvedValidationNotices();

        var errorCount = countBy(resolved, SeverityLevel.ERROR);
        var warningCount = countBy(resolved, SeverityLevel.WARNING);
        var infoCount = countBy(resolved, SeverityLevel.INFO);

        // System errors mean the validator itself failed part-way, so the notice counts
        // describe an incomplete report and cannot be called a pass.
        var systemErrorCount = notices.getSystemErrors().size();

        var valid = systemErrorCount == 0 && errorCount <= config.maxErrors() && warningCount <= config.maxWarnings();

        return new StaticFeedValidationResult(
                feedId,
                sha256,
                resolved.size(),
                errorCount,
                warningCount,
                infoCount,
                systemErrorCount,
                durationNanos,
                valid);
    }

    private static long countBy(Collection<? extends ResolvedNotice<?>> notices, SeverityLevel severity) {
        return notices.stream()
                .filter(notice -> notice.getSeverityLevel() == severity)
                .count();
    }
}
