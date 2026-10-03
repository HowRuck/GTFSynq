package org.example.gtfsynq.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the GTFS Schedule validation that runs on every ingested static feed.
 * <p>
 * Example configuration:
 *
 * <pre>
 * gtfsynq:
 *   static-validation:
 *     num-threads: 4
 *     max-errors: 0
 * </pre>
 *
 * @param enabled whether incoming static feed ingestion events are validated. When
 *        disabled, no listener is registered and archives are neither fetched nor
 *        validated. Defaults to {@code true}.
 * @param numThreads threads the validator uses for file-level rules. Validation is
 *        CPU bound and feeds are large, so this is separate from the consumer thread.
 *        Defaults to {@code 4}.
 * @param countryCode ISO 3166-1 alpha-2 code of the country the feed serves, used for
 *        phone number validation. An unrecognised value falls back to the validator's
 *        own "unknown" handling. Defaults to {@code ZZ}, the validator's sentinel for
 *        an unset country.
 * @param maxErrors error notices tolerated before a feed counts as invalid. The validator
 *        treats a GTFS spec violation ("must", "must not") as an error, so a production
 *        feed with any spec violation exceeds the default of {@code 0}.
 * @param maxWarnings warning notices tolerated before a feed counts as invalid. Warnings
 *        cover best-practice and "should" violations, which real feeds routinely contain,
 *        so the default is deliberately permissive.
 */
@ConfigurationProperties("gtfsynq.static-validation")
public record StaticFeedValidationConfig(
        @DefaultValue("true") boolean enabled,

        @DefaultValue("4") int numThreads,

        @DefaultValue("ZZ") String countryCode,

        @DefaultValue("0") long maxErrors,

        @DefaultValue("1000") long maxWarnings) {}
