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
}
