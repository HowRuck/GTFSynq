-- Persist the effective delay and the effective event time computed at write time, so every
-- aggregate reads one value instead of re-deriving it against a static revision that may move.
--
-- The published columns stay untouched:
--   * delay_seconds is the published arrival_delay, else the published departure_delay, else the
--     prediction minus the static plan for the same stop;
--   * effective_arrival_time / effective_departure_time carry the published time when one exists,
--     otherwise the static plan shifted by the delay of that event;
--   * effective_arrival_delay / effective_departure_delay are the delay of each event: its own
--     published delay, else the one implied by its own time, else the stop's delay_seconds.
--
-- The columns are nullable and metadata-only, but they still take a brief AccessExclusiveLock.
-- Existing rows stay NULL: the static plan they were compared against cannot be recovered exactly
-- after a revision change, so there is deliberately no backfill. Rewriting the history table would
-- also double its storage until vacuum and decompress any compressed chunk. Aggregates over the new
-- columns are only valid from the deploy time of this migration onward; NULL before that means
-- "not recorded", not "no delay", so readers fall back to the read-time derivation for older rows.
SET LOCAL lock_timeout = '5s';

ALTER TABLE rt_stop_time_updates_ht
    ADD COLUMN IF NOT EXISTS delay_seconds INT,
    ADD COLUMN IF NOT EXISTS effective_arrival_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS effective_departure_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS effective_arrival_delay INT,
    ADD COLUMN IF NOT EXISTS effective_departure_delay INT;

ALTER TABLE rt_stop_time_updates_hot
    ADD COLUMN IF NOT EXISTS delay_seconds INT,
    ADD COLUMN IF NOT EXISTS effective_arrival_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS effective_departure_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS effective_arrival_delay INT,
    ADD COLUMN IF NOT EXISTS effective_departure_delay INT;
