-- Adds the Polish long-distance rail source (PKP PLK, via PolishTrainsGTFS).
--
-- The value is added on its own: PostgreSQL cannot use a new enum value in the transaction that
-- adds it, and nothing here references it. No partition is created either - StaticFeedWriter
-- attaches the staging schema as the feed's partition with ATTACH PARTITION ... FOR VALUES IN on
-- the first load, and detaches whatever already exists.
ALTER TYPE feed_id_enum ADD VALUE IF NOT EXISTS 'polish-trains';
