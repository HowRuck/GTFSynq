-- Static GTFS schedule data, one current revision per feed.
--
-- Rows are keyed by feed_id so a new revision replaces the previous one for that feed alone, and
-- so every query about a feed stays inside its own slice of the tables. static_feeds records which
-- revision those rows belong to: a redelivered event with a digest that already sits there is a
-- no-op instead of a rewrite, and a failed load leaves the previous revision untouched.
CREATE TABLE static_feeds (
    feed_id feed_id_enum PRIMARY KEY,
    sha256 TEXT NOT NULL,
    bucket TEXT NOT NULL,
    object_key TEXT NOT NULL,
    source_url TEXT,
    size_bytes BIGINT NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE static_agency (
    feed_id feed_id_enum NOT NULL,
    -- Single-agency feeds omit agency_id, which is legal; the empty string stands for the one
    -- agency such a feed describes.
    agency_id TEXT NOT NULL,
    agency_name TEXT NOT NULL,
    agency_url TEXT NOT NULL,
    agency_timezone TEXT NOT NULL,
    agency_lang TEXT,
    agency_fare_url TEXT,
    agency_email TEXT,
    PRIMARY KEY (feed_id, agency_id)
);

CREATE TABLE static_stops (
    feed_id feed_id_enum NOT NULL,
    stop_id TEXT NOT NULL,
    stop_name TEXT NOT NULL,
    stop_lat DOUBLE PRECISION,
    stop_lon DOUBLE PRECISION,
    stop_url TEXT,
    location_type SMALLINT,
    parent_station TEXT,
    stop_timezone TEXT,
    wheelchair_boarding SMALLINT,
    PRIMARY KEY (feed_id, stop_id)
);

CREATE TABLE static_routes (
    feed_id feed_id_enum NOT NULL,
    route_id TEXT NOT NULL,
    route_short_name TEXT,
    route_long_name TEXT,
    route_type SMALLINT NOT NULL,
    route_url TEXT,
    route_color TEXT,
    route_text_color TEXT,
    route_sort_order INT,
    continuous_pickup SMALLINT,
    continuous_drop_off SMALLINT,
    PRIMARY KEY (feed_id, route_id)
);

CREATE TABLE static_trips (
    feed_id feed_id_enum NOT NULL,
    trip_id TEXT NOT NULL,
    route_id TEXT NOT NULL,
    service_id TEXT NOT NULL,
    direction_id SMALLINT,
    wheelchair_accessible SMALLINT,
    bikes_allowed SMALLINT,
    PRIMARY KEY (feed_id, trip_id)
);

-- A GTFS time may run past midnight, so the day overflow is kept beside the time, the same way
-- rt_trip_updates_meta does it for a trip descriptor.
CREATE TABLE static_stop_times (
    feed_id feed_id_enum NOT NULL,
    trip_id TEXT NOT NULL,
    stop_sequence INT NOT NULL,
    stop_id TEXT NOT NULL,
    arrival_time TIME,
    arrival_time_overflow_days SMALLINT,
    departure_time TIME,
    departure_time_overflow_days SMALLINT,
    pickup_type SMALLINT,
    drop_off_type SMALLINT,
    shape_dist_traveled DOUBLE PRECISION,
    timepoint SMALLINT,
    PRIMARY KEY (feed_id, trip_id, stop_sequence)
);

CREATE TABLE static_calendar (
    feed_id feed_id_enum NOT NULL,
    service_id TEXT NOT NULL,
    monday SMALLINT,
    tuesday SMALLINT,
    wednesday SMALLINT,
    thursday SMALLINT,
    friday SMALLINT,
    saturday SMALLINT,
    sunday SMALLINT,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    PRIMARY KEY (feed_id, service_id)
);

CREATE TABLE static_calendar_dates (
    feed_id feed_id_enum NOT NULL,
    service_id TEXT NOT NULL,
    date DATE NOT NULL,
    exception_type SMALLINT NOT NULL,
    PRIMARY KEY (feed_id, service_id, date)
);

-- The primary keys already lead with feed_id, so feed-scoped lookups are covered; these two serve
-- the lookups that start from the other end of the reference.
CREATE INDEX idx_static_trips_route ON static_trips (feed_id, route_id);

CREATE INDEX idx_static_stop_times_stop ON static_stop_times (feed_id, stop_id);

CREATE INDEX idx_static_trips_service ON static_trips (feed_id, service_id);