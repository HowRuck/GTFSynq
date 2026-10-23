-- Complete the static tables with every field the GTFS Schedule Reference defines for the files
-- they hold, so ingestion no longer drops optional data.
--
-- Only the files that already have a table are covered: agency, stops, routes, trips, stop_times,
-- calendar and calendar_dates. calendar and calendar_dates are already complete. Columns are
-- appended in reference order after the ones the earlier revisions created, so the existing column
-- positions and bindings keep working.
--
-- Enumerations stay SMALLINT like the other GTFS enums in this schema, and GTFS times that may run
-- past midnight keep their day overflow beside them, matching arrival_time/departure_time.
ALTER TABLE static_agency
    ADD COLUMN IF NOT EXISTS agency_phone TEXT,
    ADD COLUMN IF NOT EXISTS cemv_support SMALLINT;

ALTER TABLE static_stops
    ADD COLUMN IF NOT EXISTS stop_code TEXT,
    ADD COLUMN IF NOT EXISTS tts_stop_name TEXT,
    ADD COLUMN IF NOT EXISTS stop_desc TEXT,
    ADD COLUMN IF NOT EXISTS zone_id TEXT,
    ADD COLUMN IF NOT EXISTS level_id TEXT,
    ADD COLUMN IF NOT EXISTS platform_code TEXT,
    ADD COLUMN IF NOT EXISTS stop_access SMALLINT;

ALTER TABLE static_routes
    ADD COLUMN IF NOT EXISTS agency_id TEXT,
    ADD COLUMN IF NOT EXISTS route_desc TEXT,
    ADD COLUMN IF NOT EXISTS network_id TEXT,
    ADD COLUMN IF NOT EXISTS cemv_support SMALLINT;

ALTER TABLE static_trips
    ADD COLUMN IF NOT EXISTS trip_headsign TEXT,
    ADD COLUMN IF NOT EXISTS trip_short_name TEXT,
    ADD COLUMN IF NOT EXISTS block_id TEXT,
    ADD COLUMN IF NOT EXISTS shape_id TEXT,
    ADD COLUMN IF NOT EXISTS cars_allowed SMALLINT,
    ADD COLUMN IF NOT EXISTS safe_duration_factor DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS safe_duration_offset DOUBLE PRECISION;

ALTER TABLE static_stop_times
    ADD COLUMN IF NOT EXISTS location_group_id TEXT,
    ADD COLUMN IF NOT EXISTS location_id TEXT,
    ADD COLUMN IF NOT EXISTS stop_headsign TEXT,
    ADD COLUMN IF NOT EXISTS start_pickup_drop_off_window TIME,
    ADD COLUMN IF NOT EXISTS start_pickup_drop_off_window_overflow_days SMALLINT,
    ADD COLUMN IF NOT EXISTS end_pickup_drop_off_window TIME,
    ADD COLUMN IF NOT EXISTS end_pickup_drop_off_window_overflow_days SMALLINT,
    ADD COLUMN IF NOT EXISTS continuous_pickup SMALLINT,
    ADD COLUMN IF NOT EXISTS continuous_drop_off SMALLINT,
    ADD COLUMN IF NOT EXISTS pickup_booking_rule_id TEXT,
    ADD COLUMN IF NOT EXISTS drop_off_booking_rule_id TEXT;
