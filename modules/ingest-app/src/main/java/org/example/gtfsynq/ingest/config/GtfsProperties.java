package org.example.gtfsynq.ingest.config;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.example.gtfsynq.ingest.config.enums.GtfsStaticFeedFile;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for GTFS ingestion.
 * <p>
 * This class defines the structure for configuring GTFS feed sources in the
 * application.
 * Configuration is typically provided via application.yml or
 * application.properties.
 * <p>
 * Example configuration:
 *
 * <pre>
 * gtfs:
 *   sources:
 *     my-agency:
 *       static-config:
 *         url: {@code https://example.com/gtfs.zip}
 *       realtime-config:
 *         urls:
 *           - {@code https://example.com/trip-updates}
 *           - {@code https://example.com/vehicle-positions}
 *         poll-interval-seconds: 60
 * </pre>
 *
 * @param sources map of feed source configurations keyed by source name/identifier.
 *        Each entry defines the static and realtime configuration for a GTFS feed.
 * @param feedTimeoutSeconds per-feed timeout in seconds applied to each realtime feed poll.
 *        If a single feed poll does not complete within this window, its future is
 *        failed with a TimeoutException and the rest of the batch continues.
 *        Defaults to 25 seconds.
 */
@Validated
@ConfigurationProperties("gtfs")
@ImportRuntimeHints(GtfsPropertiesRuntimeHints.class)
public record GtfsProperties(
        Map<String, @Valid FeedSource> sources,

        @DefaultValue("25") int feedTimeoutSeconds) {

    /**
     * Feed source configuration for a GTFS feed.
     * Contains separate configurations for static data and realtime updates.
     *
     * @param staticConfig configuration for static GTFS feed data (routes, stops, schedules, etc.)
     * @param realtimeConfig configuration for realtime GTFS feed data (vehicle positions, trip updates,
     *        etc.)
     */
    public record FeedSource(
            @Valid StaticConfig staticConfig,

            @Valid RealtimeConfig realtimeConfig) {}

    /**
     * Static configuration for a GTFS feed source.
     * Only ZIP format is supported.
     *
     * @param url URL to the GTFS static feed ZIP file. Must be a valid URL.
     * @param fileUrls map of GTFS static feed files to their respective URLs.
     *        Allows specifying custom URLs for individual GTFS files.
     * @param nameMappings custom name mappings for GTFS files.
     *        Maps custom filenames to standard GTFS file types.
     * @param supportedFiles list of GTFS static feed files that are supported/expected.
     *        If not specified, defaults to the core GTFS files:
     *        AGENCY, STOPS, ROUTES, TRIPS, STOP_TIMES.
     */
    public record StaticConfig(
            @URL String url,

            Map<GtfsStaticFeedFile, @URL String> fileUrls,

            Map<String, GtfsStaticFeedFile> nameMappings,

            List<GtfsStaticFeedFile> supportedFiles) {

        /**
         * Constructor with default values for supportedFiles
         */
        public StaticConfig {
            if (supportedFiles == null) {
                supportedFiles = List.of(
                        GtfsStaticFeedFile.AGENCY,
                        GtfsStaticFeedFile.STOPS,
                        GtfsStaticFeedFile.ROUTES,
                        GtfsStaticFeedFile.TRIPS,
                        GtfsStaticFeedFile.STOP_TIMES);
            }
        }
    }

    /**
     * Realtime configuration for a GTFS feed source.
     * Uses a list of URLs for all realtime feeds regardless of message type.
     *
     * @param urls list of URLs for GTFS-RT feeds. Can include TripUpdates, VehiclePositions, Alerts, etc.
     * @param authHeaderName name of the HTTP header to use for authentication.
     * @param apiKey API key for authenticating with the GTFS-RT feed provider.
     * @param pollIntervalSeconds interval in seconds between polls for realtime updates.
     *        Defaults to 30 seconds if not specified.
     */
    public record RealtimeConfig(
            List<@URL String> urls,

            String authHeaderName,

            String apiKey,

            @DefaultValue("30") int pollIntervalSeconds) {

        /**
         * Checks if authentication is required for this realtime feed
         *
         * @return true if both authHeaderName and apiKey are provided and non-empty
         */
        public boolean requiresAuth() {
            return authHeaderName != null && !authHeaderName.isEmpty();
        }
    }
}
