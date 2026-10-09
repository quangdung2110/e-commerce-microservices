package com.ecommerce.review.config;

import com.ecommerce.review.repository.OutboxRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness: PostgreSQL, both HMAC secrets, and the outbox partition of the current UTC
 * month to write into.
 *
 * <p>Kafka and object storage are deliberately absent. A Connect outage must not make
 * review commands unavailable — committed outbox rows simply wait for CDC — and a
 * storage outage fails only the media operations that need it, with a retryable 503
 * ({@code docs/review/operations.md 2}).
 */
@Component("reviewReadiness")
public class ReviewReadinessIndicator implements HealthIndicator {

    private final OutboxRepository outbox;
    private final ReviewProperties properties;

    public ReviewReadinessIndicator(OutboxRepository outbox, ReviewProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.secretsConfigured()) {
            return Health.down().withDetail("secrets", "author alias or privacy guard HMAC secret missing").build();
        }
        try {
            // Every command writes an outbox row, and the row lands in the partition of the
            // current UTC month. Older partitions are no help: with only history present,
            // no command can commit at all.
            OutboxRepository.WindowState window = outbox.windowState();
            if (!window.currentExists()) {
                return Health.down()
                        .withDetail("outbox", "no review_outbox partition exists for the current UTC month")
                        .build();
            }
            return Health.up().withDetail("outboxNextMonthPartition", window.nextExists()).build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }
}
