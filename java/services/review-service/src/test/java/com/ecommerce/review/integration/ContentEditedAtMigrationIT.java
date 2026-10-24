package com.ecommerce.review.integration;

import com.ecommerce.review.outbox.ReviewEventData;
import com.ecommerce.review.outbox.ReviewEventFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises a real V3 -> V4 upgrade, including missing/ambiguous retained history. */
class ContentEditedAtMigrationIT extends AbstractReviewIT {
    @Autowired private ReviewEventFactory events;

    @Test
    void backfillsOnlyAuthorEvidenceAndPreservesRecordUpdateTime() throws Exception {
        String database = "edit_time_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
        try {
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas("review").target("3").load().migrate();
            JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(
                    url + (url.contains("?") ? "&" : "?") + "currentSchema=review,public",
                    POSTGRES.getUsername(), POSTGRES.getPassword()));
            UUID known = legacyReview(db, 1), missing = legacyReview(db, 1),
                    ambiguous = legacyReview(db, 1), unedited = legacyReview(db, 0);
            Instant editTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
            editEvent(db, known, editTime);
            editEvent(db, ambiguous, editTime);
            editEvent(db, ambiguous, editTime.plusSeconds(1));
            // A corrupt/inconsistent event must not claim an unedited row was edited.
            editEvent(db, unedited, editTime);
            var oldUpdated = db.queryForObject("SELECT updated_at FROM reviews WHERE id = ?", OffsetDateTime.class, known);

            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas("review").load().migrate();

            assertThat(db.queryForObject("SELECT content_edited_at FROM reviews WHERE id = ?", OffsetDateTime.class, known)
                    .toInstant()).isEqualTo(editTime);
            for (UUID id : new UUID[]{missing, ambiguous, unedited}) {
                assertThat(db.queryForObject("SELECT content_edited_at FROM reviews WHERE id = ?", OffsetDateTime.class, id)).isNull();
            }
            assertThat(db.queryForObject("SELECT updated_at FROM reviews WHERE id = ?", OffsetDateTime.class, known)).isEqualTo(oldUpdated);
            assertThat(db.queryForObject("SELECT edit_count FROM reviews WHERE id = ?", Integer.class, known)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT tgenabled FROM pg_trigger WHERE tgname='trg_review_updated_at'", String.class)).isEqualTo("O");
        } finally {
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private UUID legacyReview(JdbcTemplate db, int edits) {
        UUID eligibility = UUID.randomUUID(), review = UUID.randomUUID();
        db.update("""
                INSERT INTO review_eligibilities (id, order_id, product_id, sku_id, owner_user_id,
                    status, completed_at, expires_at, claimed_at)
                VALUES (?, ?, 'p-1', 'sku-1', ?, 'CLAIMED', now(), now()+interval '30 days', now())
                """, eligibility, UUID.randomUUID().toString(), UUID.randomUUID());
        db.update("""
                INSERT INTO reviews (id, eligibility_id, product_id, sku_id, author_display_name,
                    rating, edit_count, updated_at) VALUES (?, ?, 'p-1', 'sku-1', 'QA', 4, ?, now())
                """, review, eligibility, edits);
        return review;
    }

    private void editEvent(JdbcTemplate db, UUID id, Instant time) {
        var row = events.updated(new ReviewEventData.Updated(id, "p-1", "sku-1", 3, 4, false, false, true), time);
        db.update("""
                INSERT INTO review_outbox (event_id, event_type, aggregate_type, aggregate_id,
                    partition_key, correlation_id, payload, created_at)
                VALUES (?, ?, 'REVIEW', ?, ?, ?, ?::jsonb, ?)
                """, row.eventId(), row.eventType().type(), id.toString(), row.partitionKey(),
                row.correlationId(), row.payload(), OffsetDateTime.ofInstant(time, ZoneOffset.UTC));
    }
}
