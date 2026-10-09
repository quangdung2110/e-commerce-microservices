package com.ecommerce.review.integration;

import com.ecommerce.review.config.ReviewReadinessIndicator;
import com.ecommerce.review.repository.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every command writes an outbox row into the partition of the current UTC month, so a
 * missing current-month partition stops every write (operations.md 2).
 *
 * <p>Partitions are never dropped automatically, so counting them cannot tell the rolling
 * window from history: once old months pile up the count stays high while the one
 * partition a write needs is absent. Readiness and the missing-partition metric therefore
 * look at the two months themselves.
 */
class OutboxPartitionWindowIT extends AbstractReviewIT {

    private static final String CURRENT_PARTITION = """
            SELECT format('review_outbox_y%sm%s',
                          to_char(date_trunc('month', now() AT TIME ZONE 'UTC'), 'YYYY'),
                          to_char(date_trunc('month', now() AT TIME ZONE 'UTC'), 'MM'))
            """;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ReviewReadinessIndicator readiness;

    @Test
    void theBootstrapProvidesTheCurrentAndNextMonth() {
        assertThat(outbox.windowState().currentExists()).isTrue();
        assertThat(outbox.windowState().nextExists()).isTrue();
        assertThat(outbox.windowState().complete()).isTrue();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void historyDoesNotHideAMissingCurrentMonth() {
        String current = jdbc.sql(CURRENT_PARTITION).query(String.class).single();
        jdbc.sql("CREATE TABLE review.review_outbox_y2020m01 PARTITION OF review.review_outbox "
                + "FOR VALUES FROM ('2020-01-01') TO ('2020-02-01')").update();
        jdbc.sql("DROP TABLE review." + current).update();
        try {
            assertThat(outbox.partitionNames()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(outbox.windowState().currentExists()).isFalse();
            assertThat(outbox.windowState().complete()).isFalse();
            assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        } finally {
            outbox.ensureMonthlyPartitions();
            jdbc.sql("DROP TABLE IF EXISTS review.review_outbox_y2020m01").update();
        }

        assertThat(outbox.windowState().complete()).isTrue();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aMissingNextMonthIsReportedButTheInstanceStaysReady() {
        String next = jdbc.sql("""
                SELECT format('review_outbox_y%sm%s',
                              to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month', 'YYYY'),
                              to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + interval '1 month', 'MM'))
                """).query(String.class).single();
        jdbc.sql("DROP TABLE review." + next).update();
        try {
            assertThat(outbox.windowState().currentExists()).isTrue();
            assertThat(outbox.windowState().nextExists()).isFalse();
            assertThat(outbox.windowState().complete()).isFalse();
            // Writes still land in the current month, so traffic is safe; the metric alerts.
            assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
        } finally {
            outbox.ensureMonthlyPartitions();
        }
        assertThat(outbox.windowState().complete()).isTrue();
    }
}
