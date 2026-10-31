-- HTTP cache validators observed when a static revision was ingested.
--
-- These columns are diagnostics, not control state: the ingest app owns the conditional
-- request cache and decides whether to send If-None-Match / If-Modified-Since. Keeping the
-- values the event carried makes a stored revision explainable later - which object ETag was
-- written, what the origin advertised, and when the origin said it last changed.
--
-- source_last_modified stays TEXT because it is the origin's header value verbatim; parsing
-- it here would lose the exact bytes that were revalidated and could reject an origin using
-- an obsolete but still accepted HTTP date format.
ALTER TABLE static_feeds
    ADD COLUMN IF NOT EXISTS object_etag TEXT,
    ADD COLUMN IF NOT EXISTS source_etag TEXT,
    ADD COLUMN IF NOT EXISTS source_last_modified TEXT;
