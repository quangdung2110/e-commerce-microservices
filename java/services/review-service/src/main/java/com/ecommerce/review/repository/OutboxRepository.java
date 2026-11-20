package com.ecommerce.review.repository;

import com.ecommerce.review.outbox.OutboxRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Append-only writes into {@code review_outbox}.
 *
 * <p>There is no publish status and no {@code published_at}: Debezium does not
 * acknowledge single rows in a way Review could record, so "rows older than N minutes"
 * is not backlog and must never be read as one ({@code docs/review/operations.md 5}).
 *
 * <p>The insert joins the caller's transaction. No method here starts one.
 */
@Repository
public class OutboxRepository {

    private static final String INSERT = """
            INSERT INTO review_outbox
                (event_id, event_type, aggregate_type, aggregate_id, partition_key,
                 correlation_id, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(OutboxRow row) {
        jdbc.sql(INSERT)
                .param(row.eventId())
                .param(row.eventType().type())
                .param(row.eventType().aggregateType().name())
                .param(row.aggregateId())
                .param(row.partitionKey())
                .param(row.correlationId())
                .param(row.payload())
                .param(OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC))
                .update();
    }

    public void appendAll(List<OutboxRow> rows) {
        rows.forEach(this::append);
    }

    /**
     * Creates the current and next UTC month partitions if they are missing.
     *
     * <p>Every command writes an outbox row, so a missing partition fails every write.
     * The scheduler maintains this rolling window and its absence is a critical alert,
     * never a silent degradation (ADR-003).
     */
    public void ensureMonthlyPartitions() {
        jdbc.sql("""
                DO $$
                DECLARE
                    month_start DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::DATE;
                    partition_start DATE;
                    partition_end DATE;
                    partition_name TEXT;
                    i INTEGER;
                BEGIN
                    FOR i IN 0..1 LOOP
                        partition_start := (month_start + (i || ' month')::INTERVAL)::DATE;
                        partition_end := (partition_start + INTERVAL '1 month')::DATE;
                        partition_name := format('review_outbox_y%sm%s',
                            to_char(partition_start, 'YYYY'), to_char(partition_start, 'MM'));
                        EXECUTE format(
                            'CREATE TABLE IF NOT EXISTS review.%I PARTITION OF review.review_outbox '
                            || 'FOR VALUES FROM (%L) TO (%L)',
                            partition_name, partition_start, partition_end);
                    END LOOP;
                END $$;
                """).update();
    }

    /** The partitions that currently exist, for the readiness check and the runbooks. */
    public List<String> partitionNames() {
        return jdbc.sql("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_namespace n ON n.oid = p.relnamespace
                WHERE n.nspname = 'review' AND p.relname = 'review_outbox'
                ORDER BY c.relname
                """).query(String.class).list();
    }

    /** Whether the partitions a write can land in exist: the current and the next UTC month. */
    public record WindowState(boolean currentExists, boolean nextExists) {

        /** The rolling window the scheduler maintains is complete. */
        public boolean complete() {
            return currentExists && nextExists;
        }
    }

    /**
     * Whether the partition for the current UTC month, and the one after it, exist.
     *
     * <p>This asks about the months themselves rather than counting partitions. A count
     * cannot tell the window from history: partitions are never dropped automatically, so
     * old months keep it above any threshold while the current month is missing and every
     * command fails to write its outbox row. The names follow the convention of the
     * bootstrap and of {@link #ensureMonthlyPartitions()}.
     */
    public WindowState windowState() {
        return jdbc.sql("""
                WITH month AS (SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date AS start),
                     existing AS (
                         SELECT c.relname
                         FROM pg_inherits i
                         JOIN pg_class c ON c.oid = i.inhrelid
                         JOIN pg_class p ON p.oid = i.inhparent
                         JOIN pg_namespace n ON n.oid = p.relnamespace
                         WHERE n.nspname = 'review' AND p.relname = 'review_outbox')
                SELECT
                    EXISTS (SELECT 1 FROM existing, month
                             WHERE relname = format('review_outbox_y%sm%s',
                                   to_char(month.start, 'YYYY'), to_char(month.start, 'MM'))) AS current_exists,
                    EXISTS (SELECT 1 FROM existing, month
                             WHERE relname = format('review_outbox_y%sm%s',
                                   to_char(month.start + INTERVAL '1 month', 'YYYY'),
                                   to_char(month.start + INTERVAL '1 month', 'MM'))) AS next_exists
                """)
                .query((rs, row) -> new WindowState(rs.getBoolean("current_exists"), rs.getBoolean("next_exists")))
                .single();
    }
}
