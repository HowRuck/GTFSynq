-- One table for the poll state of static and realtime feeds.
--
-- realtime_feed_state held realtime polls only, and static polls were recorded as two columns on
-- static_feeds. Both move into feed_poll_state, keyed by kind, feed and URL, so a single event type,
-- consumer and upsert serve both. static_feeds goes back to describing the loaded revision only.
-- Validators are the origin's header values verbatim. last_changed_at is the last poll that
-- transferred a new body, last_polled_at the last poll of any outcome. feed_id is TEXT, not
-- feed_id_enum, so a feed can be tracked before its enum value exists.
-- Every poll rewrites each row, so fillfactor 90 leaves room for HOT updates; no updated column is
-- indexed.
DO $$
BEGIN
    CREATE TYPE feed_kind AS ENUM ('static', 'realtime');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END
$$;

CREATE TABLE IF NOT EXISTS feed_poll_state (
    kind                 feed_kind        NOT NULL,
    feed_id              TEXT             NOT NULL,
    source_url           TEXT             NOT NULL,
    source_etag          TEXT,
    source_last_modified TEXT,
    sha256               TEXT,
    size_bytes           BIGINT           NOT NULL DEFAULT 0,
    last_status          feed_poll_status NOT NULL,
    last_changed_at      TIMESTAMPTZ,
    last_polled_at       TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (kind, feed_id, source_url)
) WITH (fillfactor = 90);

INSERT INTO feed_poll_state (
    kind, feed_id, source_url, source_etag, source_last_modified, sha256, size_bytes,
    last_status, last_changed_at, last_polled_at)
SELECT 'realtime', feed_id, source_url, source_etag, source_last_modified, sha256, size_bytes,
       last_status, last_changed_at, last_polled_at
FROM realtime_feed_state
ON CONFLICT DO NOTHING;

INSERT INTO feed_poll_state (
    kind, feed_id, source_url, source_etag, source_last_modified, sha256, size_bytes,
    last_status, last_changed_at, last_polled_at)
SELECT 'static', feed_id::text, source_url, source_etag, source_last_modified, sha256, size_bytes,
       last_poll_status, ingested_at, last_polled_at
FROM static_feeds
WHERE last_poll_status IS NOT NULL AND last_polled_at IS NOT NULL AND source_url IS NOT NULL
ON CONFLICT DO NOTHING;

DROP TABLE realtime_feed_state;

ALTER TABLE static_feeds
    DROP COLUMN IF EXISTS last_poll_status,
    DROP COLUMN IF EXISTS last_polled_at;
