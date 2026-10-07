package com.ecommerce.review.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Readiness: PostgreSQL, both HMAC secrets, and an outbox partition to write into.
 *
 * <p>Kafka and object storage are deliberately absent. A Connect outage must not make
 * review commands unavailable — committed outbox rows simply wait for CDC — and a
 * storage outage fails only the media operations that need it, with a retryable 503
 * ({@code docs/review/operations.md 2}).
 */
@Component("reviewReadiness")
public class ReviewReadinessIndicator implements HealthIndicator {

    private final JdbcClient jdbc;
    private final ReviewProperties properties;

    public ReviewReadinessIndicator(JdbcClient jdbc, ReviewProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.secretsConfigured()) {
            return Health.down().withDetail("secrets", "author alias or privacy guard HMAC secret missing").build();
        }
        try {
            Long partitions = jdbc.sql("""
                    SELECT count(*)
                      FROM pg_inherits i
                      JOIN pg_class p ON p.oid = i.inhparent
                      JOIN pg_namespace n ON n.oid = p.relnamespace
                     WHERE n.nspname = 'review' AND p.relname = 'review_outbox'
                    """).query(Long.class).single();
            if (partitions == null || partitions == 0) {
                // Every command writes an outbox row, so no partition means no command
                // can commit at all.
                return Health.down().withDetail("outbox", "no review_outbox partition exists").build();
            }
            return Health.up().withDetail("outboxPartitions", partitions).build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }
}
