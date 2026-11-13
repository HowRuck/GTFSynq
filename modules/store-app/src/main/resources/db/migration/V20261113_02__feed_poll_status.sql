-- Poll outcomes for static feeds, and the body digest for realtime feeds.
--
-- The status enum is shared by both kinds of feed, so it loses its realtime_ prefix. Renaming a type
-- is a catalog-only change: columns keep using it and no table is rewritten.
-- static_feeds gains the outcome and time of its last poll; they are set by poll events and are
-- not touched when a revision is written. last_poll_status is NULL until the first poll after a
-- revision exists.
ALTER TYPE realtime_poll_status RENAME TO feed_poll_status;

ALTER TABLE realtime_feed_state
    ADD COLUMN IF NOT EXISTS sha256 TEXT;

ALTER TABLE static_feeds
    ADD COLUMN IF NOT EXISTS last_poll_status feed_poll_status,
    ADD COLUMN IF NOT EXISTS last_polled_at TIMESTAMPTZ;
