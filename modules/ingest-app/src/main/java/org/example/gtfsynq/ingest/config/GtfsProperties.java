package org.example.gtfsynq.ingest.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.gtfsynq.ingest.config.enums.GtfsStaticFeedFile;
import org.hibernate.validator.constraints.URL;

/**
 * Configuration properties for GTFS ingestion.
 * <p>
 * This class defines the structure for configuring GTFS feed sources in the
 * application.
 * Configuration is typically provided via application.properties.
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
 */
@ConfigMapping(prefix = "gtfs")
public interface GtfsProperties {

    /**
     * Map of feed source configurations keyed by source name/identifier. Each entry defines
     * the static and realtime configuration for a GTFS feed.
     */
    Map<String, FeedSource> sources();

    /**
     * Per-feed timeout in seconds applied to each realtime feed poll. If a single feed poll
     * does not complete within this window, its future is failed with a TimeoutException and
     * the rest of the batch continues. Defaults to 25 seconds.
     */
    @WithDefault("25")
    int feedTimeoutSeconds();

    /**
     * Timeout in seconds applied to the ingestion of a single static feed, covering download,
     * storage and event publication. Static archives are much larger than realtime payloads
     * and are transferred to object storage, so this window is generous by default. Defaults
     * to 600 seconds.
     */
    @WithDefault("600")
    int staticFeedTimeoutSeconds();

    /**
     * Feed source configuration for a GTFS feed. Contains separate configurations for static
     * data and realtime updates.
     */
    interface FeedSource {

        /**
         * Configuration for static GTFS feed data (routes, stops, schedules, etc.).
         */
        StaticConfig staticConfig();

        /**
         * Configuration for realtime GTFS feed data (vehicle positions, trip updates, etc.).
         */
        RealtimeConfig realtimeConfig();
    }

    /**
     * Static configuration for a GTFS feed source. Only ZIP format is supported.
     */
    interface StaticConfig {

        /**
         * URL to the GTFS static feed ZIP file. Must be a valid URL.
         */
        @URL
        String url();

        /**
         * Map of GTFS static feed files to their respective URLs. Allows specifying custom
         * URLs for individual GTFS files.
         */
        Map<GtfsStaticFeedFile, @URL String> fileUrls();

        /**
         * Custom name mappings for GTFS files. Maps custom filenames to standard GTFS file
         * types.
         */
        Map<String, GtfsStaticFeedFile> nameMappings();

        /**
         * List of GTFS static feed files that are supported/expected. If not specified,
         * defaults to the core GTFS files: AGENCY, STOPS, ROUTES, TRIPS, STOP_TIMES.
         */
        @WithDefault("AGENCY,STOPS,ROUTES,TRIPS,STOP_TIMES")
        List<GtfsStaticFeedFile> supportedFiles();
    }

    /**
     * Realtime configuration for a GTFS feed source. Uses a list of URLs for all realtime
     * feeds regardless of message type.
     */
    interface RealtimeConfig {

        /**
         * List of URLs for GTFS-RT feeds. Can include TripUpdates, VehiclePositions, Alerts,
         * etc.
         */
        List<@URL String> urls();

        /**
         * Name of the HTTP header to use for authentication. Empty when the feed is public.
         */
        Optional<String> authHeaderName();

        /**
         * API key for authenticating with the GTFS-RT feed provider. Empty when the feed is
         * public.
         */
        Optional<String> apiKey();

        /**
         * Interval in seconds between polls for realtime updates. Defaults to 30 seconds if
         * not specified.
         */
        @WithDefault("30")
        int pollIntervalSeconds();

        /**
         * Checks if authentication is required for this realtime feed
         *
         * @return true if both authHeaderName and apiKey are provided and non-empty
         */
        default boolean requiresAuth() {
            return authHeaderName().filter(header -> !header.isEmpty()).isPresent();
        }
    }
}
