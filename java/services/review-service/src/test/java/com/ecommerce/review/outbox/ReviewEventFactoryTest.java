package com.ecommerce.review.outbox;

import com.ecommerce.review.common.RequestContext;
import com.ecommerce.review.config.JacksonConfig;
import com.ecommerce.review.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-003: the outbox row is the wire message, so the envelope has to be schema-valid
 * before its domain transaction commits.
 *
 * <p>Every one of the eight message types is validated against its own JSON Schema, from
 * {@code contracts/events/review/v1/schemas}, exactly as the connector will deliver it.
 */
class ReviewEventFactoryTest {

    private static final UUID REVIEW = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID RECIPIENT = UUID.fromString("99999999-8888-4777-8666-555555555555");
    private static final String PRODUCT = "prod-42";
    private static final String SKU = "sku-42";
    private static final Instant NOW = Instant.parse("2026-09-09T10:15:30.123456Z");

    private final ObjectMapper mapper = new JacksonConfig().eventObjectMapper();
    private final ReviewEventFactory factory = new ReviewEventFactory(mapper);

    @AfterEach
    void clearCorrelation() {
        RequestContext.clear();
    }

    @Test
    void reviewCreatedMatchesItsSchema() {
        OutboxRow row = factory.lifecycle(ReviewEventType.REVIEW_CREATED, lifecycle(), NOW);
        assertValid(row);
        assertThat(payload(row).path("subject").asText()).isEqualTo("reviews/" + REVIEW);
    }

    @Test
    void reviewHiddenAndApprovedMatchTheirSchemas() {
        assertValid(factory.lifecycle(ReviewEventType.REVIEW_HIDDEN, lifecycle(), NOW));
        assertValid(factory.lifecycle(ReviewEventType.REVIEW_APPROVED, lifecycle(), NOW));
    }

    @Test
    void reviewUpdatedCarriesBothSidesOfTheDelta() {
        OutboxRow row = factory.updated(new ReviewEventData.Updated(
                REVIEW, PRODUCT, SKU, 4, 5, false, true, true), NOW);
        assertValid(row);

        JsonNode data = payload(row).path("data");
        assertThat(data.path("old_rating").asInt()).isEqualTo(4);
        assertThat(data.path("new_rating").asInt()).isEqualTo(5);
        assertThat(data.path("old_has_media").asBoolean()).isFalse();
        assertThat(data.path("new_has_media").asBoolean()).isTrue();
        assertThat(data.path("affects_aggregate").asBoolean()).isTrue();
    }

    @Test
    void reviewRemovedCarriesWasCountedAndNoLegalData() {
        OutboxRow row = factory.removed(new ReviewEventData.Removed(
                REVIEW, PRODUCT, SKU, 3, true, true), NOW);
        assertValid(row);

        JsonNode data = payload(row).path("data");
        assertThat(data.path("was_counted").asBoolean()).isTrue();
        // additionalProperties is false in the schema, but state the intent anyway: no
        // legal case, reason or administrator identity may ever appear here (ADR-010).
        assertThat(data.has("legal_case_id")).isFalse();
        assertThat(data.has("reason")).isFalse();
        assertThat(data.has("taken_down_by_admin_id")).isFalse();
    }

    /** The only message with a sequence, and the only one whose subject is the aggregate. */
    @Test
    void metricsSnapshotMirrorsRevisionIntoSequence() {
        OutboxRow row = factory.metrics(new ReviewEventData.Metrics(
                PRODUCT, new BigDecimal("4.33"), 3L, 2L, 7L, NOW), NOW);
        assertValid(row);

        JsonNode envelope = payload(row);
        assertThat(envelope.path("subject").asText()).isEqualTo("product-rating-aggregates/" + PRODUCT);
        assertThat(envelope.path("sequence").asLong()).isEqualTo(7L);
        assertThat(envelope.path("data").path("review_revision").asLong()).isEqualTo(7L);
        assertThat(row.aggregateId()).isEqualTo(PRODUCT);
        assertThat(row.eventType().aggregateType())
                .isEqualTo(ReviewEventType.AggregateType.REVIEW_METRIC);
    }

    @Test
    void bothNotificationRequestsCarryThreeIdentifiersAndNothingElse() {
        for (ReviewEventType type : new ReviewEventType[]{
                ReviewEventType.REVIEW_REPLY_NOTIFICATION_REQUESTED,
                ReviewEventType.REVIEW_REMOVAL_NOTIFICATION_REQUESTED}) {
            OutboxRow row = factory.notification(type,
                    new ReviewEventData.Notification(REVIEW, PRODUCT, RECIPIENT), NOW);
            assertValid(row);

            JsonNode data = payload(row).path("data");
            assertThat(data.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("review_id", "product_id", "recipient_user_id");
            // Its aggregate is the review, not the reply: ADMIN_REPLY was dropped.
            assertThat(row.eventType().aggregateType()).isEqualTo(ReviewEventType.AggregateType.REVIEW);
            assertThat(row.aggregateId()).isEqualTo(REVIEW.toString());
        }
    }

    /** ck_outbox_columns_mirror_payload compares the columns to the envelope. */
    @Test
    void projectionColumnsMirrorTheEnvelope() {
        OutboxRow row = factory.lifecycle(ReviewEventType.REVIEW_CREATED, lifecycle(), NOW);
        JsonNode envelope = payload(row);

        assertThat(envelope.path("id").asText()).isEqualTo(row.eventId().toString().toLowerCase());
        assertThat(envelope.path("type").asText()).isEqualTo(row.eventType().type());
        assertThat(envelope.path("data").path("product_id").asText()).isEqualTo(row.partitionKey());
        assertThat(envelope.has("correlationid")).isFalse();
        assertThat(row.correlationId()).isNull();
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(Instant.parse(envelope.path("time").asText())).isEqualTo(NOW);
    }

    @Test
    void correlationIdIsCarriedOnlyWhenTheRequestHadOne() {
        RequestContext.setCorrelationId("req-0001-abcdef");
        OutboxRow row = factory.lifecycle(ReviewEventType.REVIEW_CREATED, lifecycle(), NOW);

        assertValid(row);
        assertThat(payload(row).path("correlationid").asText()).isEqualTo("req-0001-abcdef");
        assertThat(row.correlationId()).isEqualTo("req-0001-abcdef");
    }

    /** ck_outbox_payload_within_channel_limit enforces the 16 KiB channel limit. */
    @Test
    void everyEnvelopeStaysFarBelowTheChannelLimit() {
        OutboxRow row = factory.removed(new ReviewEventData.Removed(
                REVIEW, PRODUCT, SKU, 5, true, true), NOW);
        assertThat(row.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(16384);
    }

    private static ReviewEventData.Lifecycle lifecycle() {
        return new ReviewEventData.Lifecycle(REVIEW, PRODUCT, SKU, 5, true);
    }

    private JsonNode payload(OutboxRow row) {
        try {
            return mapper.readTree(row.payload());
        } catch (Exception e) {
            throw new AssertionError("stored outbox payload is not JSON", e);
        }
    }

    private void assertValid(OutboxRow row) {
        assertThat(ContractSchemas.validate(row.eventType().schemaFileName(), payload(row)))
                .as("%s envelope must satisfy %s", row.eventType(), row.eventType().dataschema())
                .isEmpty();
    }
}
