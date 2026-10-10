package com.ecommerce.search.worker;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.worker.handler.AvailabilityEventHandler;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogEventHandler;
import com.ecommerce.search.worker.handler.CategoryEnrichment;
import com.ecommerce.search.worker.handler.CategoryEventHandler;
import com.ecommerce.search.worker.handler.EventBatch;
import com.ecommerce.search.worker.handler.ReviewEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the indexing worker does with a batch it could not apply. Not acknowledging is not
 * enough — the container keeps polling and the next successful batch would commit past the
 * failed one — so the batch has to be handed back with {@code nack} (ADR-005, ADR-019).
 */
class IndexingConsumerTest {

    private static final Duration BACKOFF = Duration.ofSeconds(5);
    private static final String REVIEW_EVENT = event("com.ecommerce.review.metrics-updated.v1");
    private static final String CONTROL_EVENT = event("com.ecommerce.catalog.reindex-requested.v1");
    private static final String AVAILABILITY_EVENT = event("com.ecommerce.inventory.sku-availability.changed.v1");

    private BatchExecutor executor;
    private ReviewEventHandler reviewHandler;
    private ReindexControlCoordinator control;
    private Acknowledgment ack;
    private IndexingConsumer consumer;

    @BeforeEach
    void setUp() {
        executor = mock(BatchExecutor.class);
        reviewHandler = mock(ReviewEventHandler.class);
        control = mock(ReindexControlCoordinator.class);
        ack = mock(Acknowledgment.class);

        SearchProperties properties = mock(SearchProperties.class);
        when(properties.topics()).thenReturn(new SearchProperties.Topics(
                "t.product.events", "t.category.events", "t.catalog.control.events",
                "t.inventory.stock.events.v1", "t.review.events"));
        when(properties.aliases()).thenReturn(new SearchProperties.Aliases(
                "products", "products_write", "categories", "categories_write", "search_suggestions", "search_jobs"));
        when(properties.consumer()).thenReturn(new SearchProperties.Consumer(BACKOFF));

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        consumer = new IndexingConsumer(mapper, properties, new SearchMetrics(new SimpleMeterRegistry()), executor,
                mock(CatalogEventHandler.class), mock(CategoryEventHandler.class),
                mock(AvailabilityEventHandler.class), reviewHandler, mock(CategoryEnrichment.class), control);
    }

    @Test
    void aBatchTheIndexTookIsAcknowledged() {
        when(executor.execute(anyString(), any(EventBatch.class))).thenReturn(true);

        consumer.onAvailabilityEvents(List.of(record("t.inventory.stock.events.v1", AVAILABILITY_EVENT)), ack);

        verify(ack).acknowledge();
        verify(ack, never()).nack(anyInt(), any(Duration.class));
    }

    @Test
    void aBatchTheIndexCouldNotTakeIsHandedBackToKafkaFromTheFirstRecord() {
        when(executor.execute(anyString(), any(EventBatch.class))).thenReturn(false);

        consumer.onAvailabilityEvents(List.of(record("t.inventory.stock.events.v1", AVAILABILITY_EVENT)), ack);

        verify(ack).nack(0, BACKOFF);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aRatingFailureHandsBackTheFailingRecordAndTheOnesAfterIt() {
        when(reviewHandler.handle(anyString(), any(), anyString())).thenReturn(true, false);

        consumer.onReviewEvents(List.of(
                record("t.review.events", REVIEW_EVENT),
                record("t.review.events", REVIEW_EVENT),
                record("t.review.events", REVIEW_EVENT)), ack);

        // Record 0 was applied, record 1 failed: nack(1) commits record 0 and redelivers 1 and 2.
        verify(ack).nack(1, BACKOFF);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aFullyAppliedRatingBatchIsAcknowledged() {
        when(reviewHandler.handle(anyString(), any(), anyString())).thenReturn(true);

        consumer.onReviewEvents(List.of(record("t.review.events", REVIEW_EVENT)), ack);

        verify(ack).acknowledge();
    }

    @Test
    void aControlEventIsOnlyAcknowledgedOnceItIsStored() {
        when(control.onReindexRequested(any())).thenReturn(false);

        consumer.onControlEvents(List.of(record("t.catalog.control.events", CONTROL_EVENT)), ack);

        verify(ack).nack(0, BACKOFF);
        verify(ack, never()).acknowledge();
    }

    @Test
    void aStoredControlEventIsAcknowledged() {
        when(control.onReindexRequested(any())).thenReturn(true);

        consumer.onControlEvents(List.of(record("t.catalog.control.events", CONTROL_EVENT)), ack);

        verify(control).onReindexRequested(any());
        verify(ack).acknowledge();
    }

    @Test
    void anUnrelatedTypeOnTheControlTopicIsAcknowledgedWithoutStoring() {
        consumer.onControlEvents(List.of(record("t.catalog.control.events",
                event("com.ecommerce.other.thing.v1"))), ack);

        verify(control, never()).onReindexRequested(any());
        verify(ack).acknowledge();
        verify(ack, never()).nack(eq(0), any(Duration.class));
    }

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    private static String event(String type) {
        return """
                {"specversion":"1.0","id":"evt-1","type":"%s","source":"/services/x","subject":"x/1",
                 "time":"2026-10-10T00:00:00Z","datacontenttype":"application/json","data":{"k":"v"}}
                """.formatted(type);
    }
}
