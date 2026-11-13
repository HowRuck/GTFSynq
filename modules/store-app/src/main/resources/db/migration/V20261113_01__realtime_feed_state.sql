-- Polling state of GTFS-RT feeds: one row per feed and URL, updated after every poll.
--
-- Validators are the origin's header values verbatim, like static_feeds.source_etag and
-- source_last_modified. last_changed_at is the last poll that transferred a new body, last_polled_at
-- is the last poll of any outcome. feed_id is TEXT, not feed_id_enum, so a feed can be tracked
-- before its enum value exists.
DO $$
BEGIN
    CREATE TYPE realtime_poll_status AS ENUM ('OK', 'NOT_MODIFIED', 'RATE_LIMITED', 'HTTP_ERROR', 'ERROR');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END
$$;

CREATE TABLE IF NOT EXISTS realtime_feed_state (
    feed_id              TEXT        NOT NULL,
    source_url           TEXT        NOT NULL,
    source_etag          TEXT,
    source_last_modified TEXT,
    last_status          realtime_poll_status NOT NULL,
    size_bytes           BIGINT      NOT NULL DEFAULT 0,
    last_changed_at      TIMESTAMPTZ,
    last_polled_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (feed_id, source_url)
) WITH (fillfactor = 90);
