-- Monthly partition management (09-data-model.md §9). The function is called
-- here for the previous, current and next two months, and daily by the
-- partition worker (workers/partition). Partition names: <table>_yYYYYmMM.

CREATE OR REPLACE FUNCTION notification_partitioned_tables()
RETURNS TABLE (table_name text, partition_column text)
LANGUAGE sql IMMUTABLE AS $$
    VALUES ('notification_request', 'received_at'),
           ('notification_delivery', 'created_at'),
           ('provider_event', 'received_at'),
           ('inbox_item', 'created_at')
$$;

CREATE OR REPLACE FUNCTION notification_partition_name(p_table text, p_month date)
RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT p_table || '_y' || to_char(p_month, 'YYYY') || 'm' || to_char(p_month, 'MM')
$$;

-- Creates the partition of p_table for the month containing p_month if absent.
CREATE OR REPLACE FUNCTION notification_ensure_partition(p_table text, p_month date)
RETURNS boolean
LANGUAGE plpgsql AS $$
DECLARE
    v_start date := date_trunc('month', p_month)::date;
    v_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_name  text := notification_partition_name(p_table, v_start);
BEGIN
    IF to_regclass(v_name) IS NOT NULL THEN
        RETURN false;
    END IF;
    EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                   v_name, p_table, v_start, v_end);
    IF p_table = 'notification_delivery' THEN
        -- Hot table: 2 UPDATE/row; keep dead tuples low so idx_delivery_ready
        -- stays small (02-pipeline.md §5.7, 09-data-model.md §4.2).
        EXECUTE format('ALTER TABLE %I SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_analyze_scale_factor = 0.005)', v_name);
    END IF;
    RETURN true;
END;
$$;

-- Ensures partitions from (now - 1 month) through (now + p_ahead months) for
-- every partitioned table.
CREATE OR REPLACE FUNCTION notification_ensure_partitions(p_now timestamptz, p_ahead int)
RETURNS int
LANGUAGE plpgsql AS $$
DECLARE
    r       record;
    i       int;
    created int := 0;
BEGIN
    FOR r IN SELECT * FROM notification_partitioned_tables() LOOP
        FOR i IN -1 .. p_ahead LOOP
            IF notification_ensure_partition(r.table_name,
                   (date_trunc('month', p_now) + make_interval(months => i))::date) THEN
                created := created + 1;
            END IF;
        END LOOP;
    END LOOP;
    RETURN created;
END;
$$;

-- Number of whole future months (after the current one) that have a partition.
CREATE OR REPLACE FUNCTION notification_partition_future_months(p_table text, p_now timestamptz)
RETURNS int
LANGUAGE plpgsql AS $$
DECLARE
    n int := 0;
    m date := (date_trunc('month', p_now) + interval '1 month')::date;
BEGIN
    WHILE to_regclass(notification_partition_name(p_table, m)) IS NOT NULL LOOP
        n := n + 1;
        m := (m + interval '1 month')::date;
    END LOOP;
    RETURN n;
END;
$$;

-- Lists partitions of p_table whose upper bound is before p_before.
CREATE OR REPLACE FUNCTION notification_expired_partitions(p_table text, p_before timestamptz)
RETURNS TABLE (partition_name text, range_end timestamptz)
LANGUAGE plpgsql AS $$
DECLARE
    r record;
    v_expr text;
    v_end  timestamptz;
BEGIN
    FOR r IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = p_table
    LOOP
        v_expr := pg_get_expr(
            (SELECT relpartbound FROM pg_class WHERE relname = r.relname),
            (SELECT oid FROM pg_class WHERE relname = r.relname));
        -- FOR VALUES FROM ('2026-09-01') TO ('2026-10-01')
        v_end := substring(v_expr FROM 'TO \(''([^'']+)''\)')::timestamptz;
        IF v_end IS NOT NULL AND v_end <= p_before THEN
            partition_name := r.relname;
            range_end := v_end;
            RETURN NEXT;
        END IF;
    END LOOP;
END;
$$;

SELECT notification_ensure_partitions(now(), 2);
