package com.ecommerce.review.integration;

import com.ecommerce.review.dto.CommandDtos;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.repository.OutboxRepository;
import com.ecommerce.review.service.ReviewCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-003: the outbox row is the message, so the database refuses a row that could not
 * be published correctly.
 *
 * <p>These are write-path failures on purpose. An envelope missing an attribute is not a
 * bug someone finds downstream days later; it fails the domain transaction that tried to
 * create it.
 */
class OutboxConstraintIT extends AbstractReviewIT {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ReviewCommandService reviewCommands;

    private final UUID buyer = UUID.randomUUID();

    /** The scheduler keeps a rolling two-month window; bootstrap created both. */
    @Test
    void theCurrentAndNextMonthPartitionsExist() {
        String currentMonth = partitionName(0);
        String nextMonth = partitionName(1);

        assertThat(outbox.partitionNames()).contains(currentMonth, nextMonth);

        // Re-running the maintenance is idempotent, which is what lets the five-minute
        // pass call it unconditionally.
        outbox.ensureMonthlyPartitions();
        assertThat(outbox.partitionNames()).contains(currentMonth, nextMonth);
    }

    @Test
    void anEnvelopeWithoutSubjectIsRejected() {
        assertThatThrownBy(() -> insertRawOutbox(envelope().replace("\"subject\":\"reviews/%s\","
                        .formatted(REVIEW_ID), "")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_payload_is_cloudevent");
    }

    @Test
    void anEnvelopeWithoutDataschemaIsRejected() {
        String withoutDataschema = envelope().replace(
                "\"dataschema\":\"/contracts/events/review/v1/schemas/review-created.schema.json\",", "");

        assertThatThrownBy(() -> insertRawOutbox(withoutDataschema))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_payload_is_cloudevent");
    }

    @Test
    void anEnvelopeWithoutTimeIsRejected() {
        String withoutTime = envelope().replace("\"time\":\"2026-09-09T10:15:30.123456Z\",", "");

        assertThatThrownBy(() -> insertRawOutbox(withoutTime))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_payload_is_cloudevent");
    }

    /**
     * The projection columns exist so Debezium can route without parsing JSON, and the
     * CHECK is what stops them drifting from the envelope they mirror.
     */
    @Test
    void aPartitionKeyThatDisagreesWithTheEnvelopeIsRejected() {
        assertThatThrownBy(() -> jdbc.sql(INSERT_RAW)
                .param(EVENT_ID)
                .param(ReviewEventType.REVIEW_CREATED.type())
                .param("REVIEW")
                .param(REVIEW_ID.toString())
                .param("a-different-product")
                .param(envelope())
                .param(OffsetDateTime.now(ZoneOffset.UTC))
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_columns_mirror_payload");
    }

    @Test
    void anEventTypeThatDisagreesWithTheEnvelopeIsRejected() {
        assertThatThrownBy(() -> jdbc.sql(INSERT_RAW)
                .param(EVENT_ID)
                .param(ReviewEventType.REVIEW_HIDDEN.type())
                .param("REVIEW")
                .param(REVIEW_ID.toString())
                .param("p-1")
                .param(envelope())
                .param(OffsetDateTime.now(ZoneOffset.UTC))
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_columns_mirror_payload");
    }

    /**
     * A payload over the 16 KiB the AsyncAPI channel declares fails the command rather
     * than committing a row CDC could never publish.
     */
    @Test
    void aPayloadAboveSixteenKibibytesIsRejectedAtWriteTime() {
        String oversized = envelope().replace("\"sku_id\":\"sku-1\"",
                "\"sku_id\":\"" + "s".repeat(17_000) + "\"");

        assertThatThrownBy(() -> insertRawOutbox(oversized))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_payload_within_channel_limit");
    }

    /** Only the two documented aggregate types are storable. */
    @Test
    void anUnknownAggregateTypeIsRejected() {
        assertThatThrownBy(() -> jdbc.sql(INSERT_RAW)
                .param(EVENT_ID)
                .param(ReviewEventType.REVIEW_CREATED.type())
                .param("ADMIN_REPLY")
                .param(REVIEW_ID.toString())
                .param("p-1")
                .param(envelope())
                .param(OffsetDateTime.now(ZoneOffset.UTC))
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_aggregate_type");
    }

    /** Every command routes by product, which is what preserves per-product ordering. */
    @Test
    void everyCommandWritesTheProductAsPartitionKey() {
        UUID eligibilityId = grantEntitlement("order-1", "p-key", "sku-1", buyer);
        reviewCommands.create(buyer, new CommandDtos.CreateReviewRequest(eligibilityId, 5, null, null));

        assertThat(jdbc.sql("SELECT DISTINCT partition_key FROM review_outbox").query(String.class).list())
                .containsExactly("p-key");
    }

    private static final UUID EVENT_ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final UUID REVIEW_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final String INSERT_RAW = """
            INSERT INTO review_outbox
                (event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    /** A well-formed envelope the tests then break one attribute at a time. */
    private static String envelope() {
        return ("{\"specversion\":\"1.0\",\"id\":\"%s\",\"type\":\"%s\",\"source\":\"/services/review\","
                + "\"subject\":\"reviews/%s\",\"time\":\"2026-09-09T10:15:30.123456Z\","
                + "\"datacontenttype\":\"application/json\","
                + "\"dataschema\":\"/contracts/events/review/v1/schemas/review-created.schema.json\","
                + "\"data\":{\"review_id\":\"%s\",\"product_id\":\"p-1\",\"sku_id\":\"sku-1\","
                + "\"rating\":5,\"has_media\":false}}")
                .formatted(EVENT_ID, ReviewEventType.REVIEW_CREATED.type(), REVIEW_ID, REVIEW_ID);
    }

    private void insertRawOutbox(String payload) {
        jdbc.sql(INSERT_RAW)
                .param(EVENT_ID)
                .param(ReviewEventType.REVIEW_CREATED.type())
                .param("REVIEW")
                .param(REVIEW_ID.toString())
                .param("p-1")
                .param(payload)
                .param(OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private static String partitionName(int monthsAhead) {
        OffsetDateTime month = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .withDayOfMonth(1)
                .plusMonths(monthsAhead);
        return "review_outbox_y%04dm%02d".formatted(month.getYear(), month.getMonthValue());
    }
}
