-- Indexes and counters for the read-only API used by the web UI.
--
-- Static tables are bulk-loaded once per feed revision, so the extra index maintenance is paid
-- during ingestion instead of during interactive queries. The hot table keeps changing, so its
-- composite index is deliberately limited to the feed filter plus the default sort.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Global default sorts used when the UI browses "All feeds".
CREATE INDEX IF NOT EXISTS idx_static_routes_route_id ON static_routes (route_id);

CREATE INDEX IF NOT EXISTS idx_static_stops_stop_name ON static_stops (stop_name, stop_id);

CREATE INDEX IF NOT EXISTS idx_static_trips_trip_id ON static_trips (trip_id);

-- Trigram indexes matching the concatenated search expressions in GtfsQueryRepository.
CREATE INDEX IF NOT EXISTS idx_static_routes_search_trgm ON static_routes USING gin (
    (route_id || ' ' || coalesce(route_short_name, '') || ' ' || coalesce(route_long_name, '')) gin_trgm_ops
);

CREATE INDEX IF NOT EXISTS idx_static_stops_search_trgm ON static_stops USING gin (
    (stop_id || ' ' || stop_name) gin_trgm_ops
);

CREATE INDEX IF NOT EXISTS idx_static_trips_search_trgm ON static_trips USING gin (
    (trip_id || ' ' || route_id || ' ' || service_id) gin_trgm_ops
);

-- Feed-filtered live page: WHERE feed_id = ? ORDER BY last_seen_at DESC.
CREATE INDEX IF NOT EXISTS idx_rt_trip_updates_hot_feed_seen
    ON rt_trip_updates_hot (feed_id, last_seen_at DESC);

-- Exact static counts are maintained by the store app when a revision is committed, so the
-- overview no longer has to count the three large static tables on every request.
ALTER TABLE static_feeds
    ADD COLUMN IF NOT EXISTS route_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS stop_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS trip_count BIGINT NOT NULL DEFAULT 0;

UPDATE static_feeds f
SET route_count = COALESCE((SELECT count(*) FROM static_routes r WHERE r.feed_id = f.feed_id), 0),
    stop_count = COALESCE((SELECT count(*) FROM static_stops s WHERE s.feed_id = f.feed_id), 0),
    trip_count = COALESCE((SELECT count(*) FROM static_trips t WHERE t.feed_id = f.feed_id), 0);
