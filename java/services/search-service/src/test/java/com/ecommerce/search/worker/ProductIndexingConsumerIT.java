package com.ecommerce.search.worker;

import com.ecommerce.search.common.EventType;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link ProductIndexingConsumer}.
 *
 * <p>These tests verify the consumer's batch-processing logic in isolation:
 * event-type allowlist filtering, null-envelope handling, and dispatch to
 * {@link EventHandler}. No Kafka or Elasticsearch infrastructure is required.
 *
 * <p>Full integration tests (Testcontainers Kafka + ES) will be added in
 * Module 08 alongside the real {@link EventHandler}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductIndexingConsumer unit tests")
class ProductIndexingConsumerIT {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Mock
    private EventHandler eventHandler;

    @Mock
    private Acknowledgment ack;

    private ProductIndexingConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductIndexingConsumer(eventHandler);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private CloudEventEnvelope envelope(String eventType) {
        return new CloudEventEnvelope(
            java.util.UUID.randomUUID().toString(),
            eventType,
            "Product",
            "prod-001",
            "corr-001",
            "prod-001",
            Instant.now().toString(),
            "product-service",
            1,
            MAPPER.valueToTree(Map.of("product_id", "prod-001"))
        );
    }

    private ConsumerRecord<String, CloudEventEnvelope> record(
            String topic, String key, CloudEventEnvelope value) {
        return new ConsumerRecord<>(topic, 0, 0L, key, value);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Should dispatch valid PRODUCT_CREATED event to EventHandler")
    void shouldDispatchValidEvent() {
        // Given
        CloudEventEnvelope envelope = envelope("PRODUCT_CREATED");
        ConsumerRecord<String, CloudEventEnvelope> record =
            record("t.product.events", "prod-001", envelope);

        // When
        consumer.onBatch(List.of(record), ack);

        // Then
        verify(eventHandler).handle(envelope);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Should dispatch multiple event types in one batch")
    void shouldDispatchMultipleEventTypes() {
        // Given
        CloudEventEnvelope productEvent = envelope("PRODUCT_UPDATED");
        CloudEventEnvelope categoryEvent = new CloudEventEnvelope(
            java.util.UUID.randomUUID().toString(),
            "CATEGORY_CREATED", "Category", "cat-001", "corr-002", "cat-001",
            Instant.now().toString(), "product-service", 1,
            MAPPER.valueToTree(Map.of("category_id", "cat-001"))
        );
        CloudEventEnvelope reviewEvent = new CloudEventEnvelope(
            java.util.UUID.randomUUID().toString(),
            "REVIEW_METRICS_UPDATED", "Review", "prod-001", "corr-003", "prod-001",
            Instant.now().toString(), "review-service", 1,
            MAPPER.valueToTree(Map.of("product_id", "prod-001", "average_rating", 4.5))
        );

        List<ConsumerRecord<String, CloudEventEnvelope>> records = List.of(
            record("t.product.events", "prod-001", productEvent),
            record("t.category.events", "cat-001", categoryEvent),
            record("t.review.events", "prod-001", reviewEvent)
        );

        // When
        consumer.onBatch(records, ack);

        // Then
        verify(eventHandler).handle(productEvent);
        verify(eventHandler).handle(categoryEvent);
        verify(eventHandler).handle(reviewEvent);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Should skip null envelope and continue processing other records")
    void shouldSkipNullEnvelope() {
        // Given — a null envelope followed by a valid one
        ConsumerRecord<String, CloudEventEnvelope> nullRecord =
            new ConsumerRecord<>("t.product.events", 0, 0L, "key-null", null);
        CloudEventEnvelope validEnvelope = envelope("SKU_CREATED");
        ConsumerRecord<String, CloudEventEnvelope> validRecord =
            record("t.product.events", "prod-002", validEnvelope);

        // When
        consumer.onBatch(List.of(nullRecord, validRecord), ack);

        // Then — EventHandler receives only the valid record
        verify(eventHandler).handle(validEnvelope);
        verify(eventHandler, never()).handle(null);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Should ignore unknown event_type not in the allowlist")
    void shouldIgnoreUnknownEventType() {
        // Given
        CloudEventEnvelope envelope = envelope("UNKNOWN_EVENT_TYPE");
        ConsumerRecord<String, CloudEventEnvelope> record =
            record("t.product.events", "prod-001", envelope);

        // When
        consumer.onBatch(List.of(record), ack);

        // Then
        verifyNoInteractions(eventHandler);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Should ignore disallowed event type (PRODUCT_STATUS_CHANGED)")
    void shouldIgnoreDisallowedEventType() {
        // Given
        CloudEventEnvelope envelope = envelope("PRODUCT_STATUS_CHANGED");
        ConsumerRecord<String, CloudEventEnvelope> record =
            record("t.product.events", "prod-001", envelope);

        // When
        consumer.onBatch(List.of(record), ack);

        // Then — PRODUCT_STATUS_CHANGED has allowedInConsumerGroup = false
        verifyNoInteractions(eventHandler);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("Should handle mixed batch with valid, unknown, and null records")
    void shouldHandleMixedBatch() {
        // Given
        CloudEventEnvelope validEnvelope = envelope("PRODUCT_CREATED");
        CloudEventEnvelope unknownEnvelope = envelope("SOME_FUTURE_EVENT");
        CloudEventEnvelope disallowedEnvelope = envelope("PRODUCT_STATUS_CHANGED");

        List<ConsumerRecord<String, CloudEventEnvelope>> records = List.of(
            record("t.product.events", "valid", validEnvelope),
            new ConsumerRecord<>("t.product.events", 0, 1L, "null-key", null),
            record("t.product.events", "unknown", unknownEnvelope),
            record("t.product.events", "legacy", disallowedEnvelope)
        );

        // When
        consumer.onBatch(records, ack);

        // Then — only the valid event reaches EventHandler
        verify(eventHandler).handle(validEnvelope);
        verify(eventHandler, never()).handle(unknownEnvelope);
        verify(eventHandler, never()).handle(disallowedEnvelope);
        verify(ack).acknowledge();
    }

    // ---------------------------------------------------------------
    // EventType enum unit tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("EventType enum")
    class EventTypeTest {

        @Test
        @DisplayName("fromString should return matching enum constant")
        void fromStringShouldMatch() {
            assertThat(EventType.fromString("PRODUCT_CREATED"))
                .isEqualTo(EventType.PRODUCT_CREATED);
            assertThat(EventType.fromString("SKU_UPDATED"))
                .isEqualTo(EventType.SKU_UPDATED);
            assertThat(EventType.fromString("REVIEW_METRICS_UPDATED"))
                .isEqualTo(EventType.REVIEW_METRICS_UPDATED);
        }

        @Test
        @DisplayName("fromString should return null for unknown type")
        void fromStringShouldReturnNullForUnknown() {
            assertThat(EventType.fromString("NONEXISTENT_EVENT")).isNull();
        }

        @Test
        @DisplayName("fromString should return null for null or blank input")
        void fromStringShouldReturnNullForBlank() {
            assertThat(EventType.fromString(null)).isNull();
            assertThat(EventType.fromString("")).isNull();
            assertThat(EventType.fromString("  ")).isNull();
        }

        @Test
        @DisplayName("isAllowedInConsumerGroup should be true for active event types")
        void activeEventsShouldBeAllowed() {
            assertThat(EventType.PRODUCT_CREATED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.PRODUCT_UPDATED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.SKU_UPDATED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.SKU_CREATED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.PRODUCT_DELETED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.SKU_DELETED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.CATEGORY_CREATED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.CATEGORY_UPDATED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.CATEGORY_DELETED.isAllowedInConsumerGroup()).isTrue();
            assertThat(EventType.REVIEW_METRICS_UPDATED.isAllowedInConsumerGroup()).isTrue();
        }

        @Test
        @DisplayName("PRODUCT_STATUS_CHANGED should not be allowed (Phase 19 legacy)")
        void productStatusChangedShouldNotBeAllowed() {
            assertThat(EventType.PRODUCT_STATUS_CHANGED.isAllowedInConsumerGroup()).isFalse();
        }

        @Test
        @DisplayName("topic() should return correct topic for each event type")
        void topicShouldReturnCorrectName() {
            // Product events
            assertThat(EventType.PRODUCT_CREATED.topic()).isEqualTo("t.product.events");
            assertThat(EventType.PRODUCT_UPDATED.topic()).isEqualTo("t.product.events");
            assertThat(EventType.SKU_UPDATED.topic()).isEqualTo("t.product.events");
            assertThat(EventType.SKU_CREATED.topic()).isEqualTo("t.product.events");
            assertThat(EventType.PRODUCT_DELETED.topic()).isEqualTo("t.product.events");
            assertThat(EventType.SKU_DELETED.topic()).isEqualTo("t.product.events");

            // Category events
            assertThat(EventType.CATEGORY_CREATED.topic()).isEqualTo("t.category.events");
            assertThat(EventType.CATEGORY_UPDATED.topic()).isEqualTo("t.category.events");
            assertThat(EventType.CATEGORY_DELETED.topic()).isEqualTo("t.category.events");

            // Review events
            assertThat(EventType.REVIEW_METRICS_UPDATED.topic()).isEqualTo("t.review.events");
        }
    }
}
