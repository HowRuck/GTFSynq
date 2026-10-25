-- Partition the static tables by feed so a revision can swap a feed's partition instead of
-- deleting and re-inserting tens of millions of rows inside one long transaction.
--
-- Each existing table is rebuilt as a LIST-partitioned table with one partition per feed id. The
-- table names, columns, constraints and indexes are preserved (LIKE ... INCLUDING ALL), so the
-- application sees the same relations. Partition names follow the convention
-- <table>_p_<feed with '-' replaced by '_'>, which the loader uses when it swaps a revision in.
SET LOCAL synchronous_commit = off;

DO $$
DECLARE
    table_name text;
    new_table text;
    feed text;
    partition text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'static_agency',
        'static_stops',
        'static_routes',
        'static_trips',
        'static_stop_times',
        'static_calendar',
        'static_calendar_dates'
    ]
    LOOP
        new_table := table_name || '_new';

        EXECUTE format(
            'CREATE TABLE %I (LIKE %I INCLUDING ALL) PARTITION BY LIST (feed_id)',
            new_table, table_name);

        FOR feed IN SELECT unnest(enum_range(NULL::feed_id_enum))
        LOOP
            partition := table_name || '_p_' || replace(feed, '-', '_');
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF %I FOR VALUES IN (%L)',
                partition, new_table, feed);
            EXECUTE format(
                'INSERT INTO %I SELECT * FROM %I WHERE feed_id = %L',
                new_table, table_name, feed);
        END LOOP;

        EXECUTE format('DROP TABLE %I', table_name);
        EXECUTE format('ALTER TABLE %I RENAME TO %I', new_table, table_name);
    END LOOP;
END $$;
