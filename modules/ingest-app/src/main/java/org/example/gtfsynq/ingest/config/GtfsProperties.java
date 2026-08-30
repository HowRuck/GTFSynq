package org.example.gtfsynq.ingest.config;

import java.util.List;
import java.util.Map;

import org.example.gtfsynq.ingest.config.enums.GtfsStaticFeedFile;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;

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
 *         url: https://example.com/gtfs.zip
 *       realtime-config:
 *         urls:
 *           - https://example.com/trip-updates
 *           - https://example.com/vehicle-positions
 *         poll-interval-seconds: 60
 * </pre>
 */
@Validated
@ConfigurationProperties("gtfs")
public record GtfsProperties(
		/**
		 * Map of feed source configurations keyed by source name/identifier.
		 * Each entry defines the static and realtime configuration for a GTFS feed.
		 */
		Map<String, @Valid FeedSource> sources,

		/**
		 * Per-feed timeout in seconds applied to each realtime feed poll.
		 * If a single feed poll does not complete within this window, its future is
		 * failed with a TimeoutException and the rest of the batch continues.
		 * Defaults to 25 seconds.
		 */
		@DefaultValue("25") int feedTimeoutSeconds) {

	/**
	 * Feed source configuration for a GTFS feed.
	 * Contains separate configurations for static data and realtime updates.
	 */
	public record FeedSource(
			/**
			 * Configuration for static GTFS feed data (routes, stops, schedules, etc.)
			 */
			@Valid StaticConfig staticConfig,

			/**
			 * Configuration for realtime GTFS feed data (vehicle positions, trip updates,
			 * etc.)
			 */
			@Valid RealtimeConfig realtimeConfig) {
	}

	/**
	 * Static configuration for a GTFS feed source.
	 * Only ZIP format is supported.
	 */
	public record StaticConfig(
			/**
			 * URL to the GTFS static feed ZIP file
			 * Must be a valid URL
			 */
			@URL String url,

			/**
			 * Map of GTFS static feed files to their respective URLs
			 * Allows specifying custom URLs for individual GTFS files
			 */
			Map<GtfsStaticFeedFile, @URL String> fileUrls,

			/**
			 * Custom name mappings for GTFS files
			 * Maps custom filenames to standard GTFS file types
			 */
			Map<String, GtfsStaticFeedFile> nameMappings,

			/**
			 * List of GTFS static feed files that are supported/expected
			 * If not specified, defaults to the core GTFS files:
			 * AGENCY, STOPS, ROUTES, TRIPS, STOP_TIMES
			 */
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
	 * Realtime configuration for a GTFS feed source
	 * Uses a list of URLs for all realtime feeds regardless of message type
	 */
	public record RealtimeConfig(
			/**
			 * List of URLs for GTFS-RT feeds
			 * Can include TripUpdates, VehiclePositions, Alerts, etc.
			 */
			List<@URL String> urls,

			/**
			 * Name of the HTTP header to use for authentication
			 */
			String authHeaderName,

			/**
			 * API key for authenticating with the GTFS-RT feed provider
			 */
			String apiKey,

			/**
			 * Interval in seconds between polls for realtime updates.
			 * Defaults to 30 seconds if not specified.
			 */
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
