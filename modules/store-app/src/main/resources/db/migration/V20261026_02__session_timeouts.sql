-- Leaked read transactions (a client that reads static tables and never commits) hold ACCESS
-- SHARE locks that block the partition swap's DETACH/ATTACH. Terminate those sessions instead of
-- letting every static revision stall on them.
--
-- ALTER DATABASE cannot take an expression, so the database name is formatted at runtime. The
-- setting applies to sessions created after this migration.
DO $$
BEGIN
    EXECUTE format(
        'ALTER DATABASE %I SET idle_in_transaction_session_timeout = %L',
        current_database(),
        '120s');
END $$;
