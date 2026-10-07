package com.ecommerce.search.worker;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.KafkaConsumerConfig;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.event.CatalogEventType;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.worker.handler.AvailabilityEventHandler;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogEventHandler;
import com.ecommerce.search.worker.handler.CategoryEnrichment;
import com.ecommerce.search.worker.handler.CategoryEventHandler;
import com.ecommerce.search.worker.handler.EventBatch;
import com.ecommerce.search.worker.handler.ReviewEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The indexing worker: one consumer group, {@code search-indexer}, over the four data
 * topics.
 *
 * <p>Every listener follows the same shape — parse the envelope, filter on the type
 * allowlist, build the writes, send one bulk, then acknowledge. The offset is committed
 * only when the batch executor says every record either applied or is beyond recovery.
 */
@Component
public class IndexingConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexingConsumer.class);

    private final ObjectMapper objectMapper;
    private final SearchProperties properties;
    private final SearchMetrics metrics;
    private final BatchExecutor executor;
    private final CatalogEventHandler catalogHandler;
    private final CategoryEventHandler categoryHandler;
    private final AvailabilityEventHandler availabilityHandler;
    private final ReviewEventHandler reviewHandler;
    private final CategoryEnrichment enrichment;
    private final ReindexControlCoordinator reindexControl;

    public IndexingConsumer(ObjectMapper esObjectMapper,
                            SearchProperties properties,
                            SearchMetrics metrics,
                            BatchExecutor executor,
                            CatalogEventHandler catalogHandler,
                            CategoryEventHandler categoryHandler,
                            AvailabilityEventHandler availabilityHandler,
                            ReviewEventHandler reviewHandler,
                            CategoryEnrichment enrichment,
                            ReindexControlCoordinator reindexControl) {
        this.objectMapper = esObjectMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.executor = executor;
        this.catalogHandler = catalogHandler;
        this.categoryHandler = categoryHandler;
        this.availabilityHandler = availabilityHandler;
        this.reviewHandler = reviewHandler;
        this.enrichment = enrichment;
        this.reindexControl = reindexControl;
    }

    @KafkaListener(
            topics = "${search.topics.product}",
            groupId = "${search.consumer-group:search-indexer}",
            containerFactory = KafkaConsumerConfig.BATCH_FACTORY)
    public void onCatalogEvents(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        String topic = properties.topics().product();
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        CategoryEnrichment.Cache cache = enrichment.newCache();

        for (ConsumerRecord<String, String> record : records) {
            dispatch(topic, record).ifPresent(typed ->
                    catalogHandler.handle(topic, typed.event(), typed.type(), batch, cache));
        }
        commitIfComplete(topic, batch, ack);
    }

    @KafkaListener(
            topics = "${search.topics.category}",
            groupId = "${search.consumer-group:search-indexer}",
            containerFactory = KafkaConsumerConfig.BATCH_FACTORY)
    public void onCategoryEvents(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        String topic = properties.topics().category();
        EventBatch batch = new EventBatch(properties.aliases().categoriesWrite());

        for (ConsumerRecord<String, String> record : records) {
            dispatch(topic, record).ifPresent(typed ->
                    categoryHandler.handle(topic, typed.event(), typed.type(), batch));
        }
        commitIfComplete(topic, batch, ack);
    }

    @KafkaListener(
            topics = "${search.topics.stock}",
            groupId = "${search.consumer-group:search-indexer}",
            containerFactory = KafkaConsumerConfig.BATCH_FACTORY)
    public void onAvailabilityEvents(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        String topic = properties.topics().stock();
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());

        for (ConsumerRecord<String, String> record : records) {
            dispatch(topic, record)
                    .filter(typed -> typed.type() == CatalogEventType.SKU_AVAILABILITY_CHANGED)
                    .ifPresent(typed -> availabilityHandler.handle(topic, typed.event(), batch));
        }
        commitIfComplete(topic, batch, ack);
    }

    /**
     * Rating is applied with {@code _update_by_query} per event rather than through the
     * bulk batch, because the target is every SKU of a product rather than one document.
     */
    @KafkaListener(
            topics = "${search.topics.review}",
            groupId = "${search.consumer-group:search-indexer}",
            containerFactory = KafkaConsumerConfig.BATCH_FACTORY)
    public void onReviewEvents(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        String topic = properties.topics().review();
        String target = properties.aliases().productsWrite();
        boolean commit = true;

        for (ConsumerRecord<String, String> record : records) {
            Optional<TypedEvent> typed = dispatch(topic, record)
                    .filter(event -> event.type() == CatalogEventType.REVIEW_METRICS_UPDATED);
            if (typed.isPresent() && !reviewHandler.handle(topic, typed.get().event(), target)) {
                commit = false;
                break;
            }
        }
        if (commit) {
            ack.acknowledge();
        }
    }

    /**
     * Control events do not touch documents; they ask Search to coalesce a reindex
     * ({@code WF-SEARCH-006} step 3). Acknowledging immediately is safe: the coalescing
     * key makes a repeated request harmless.
     */
    @KafkaListener(
            topics = "${search.topics.catalog-control}",
            groupId = "${search.consumer-group:search-indexer}",
            containerFactory = KafkaConsumerConfig.BATCH_FACTORY)
    public void onControlEvents(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        String topic = properties.topics().catalogControl();
        for (ConsumerRecord<String, String> record : records) {
            dispatch(topic, record)
                    .filter(typed -> typed.type() == CatalogEventType.CATALOG_REINDEX_REQUESTED)
                    .ifPresent(typed -> reindexControl.onReindexRequested(typed.event()));
        }
        ack.acknowledge();
    }

    private void commitIfComplete(String topic, EventBatch batch, Acknowledgment ack) {
        if (executor.execute(topic, batch)) {
            ack.acknowledge();
        } else {
            log.warn("offset for {} left uncommitted; Kafka will redeliver the batch", topic);
        }
    }

    private record TypedEvent(CloudEventEnvelope event, CatalogEventType type) {
    }

    /**
     * Parses the envelope and resolves the type. An unparsable record and a type outside
     * the allowlist are both acknowledged; only the metric differs, because the first is a
     * contract violation and the second is another consumer's traffic.
     */
    private Optional<TypedEvent> dispatch(String topic, ConsumerRecord<String, String> record) {
        CloudEventEnvelope event;
        try {
            event = objectMapper.readValue(record.value(), CloudEventEnvelope.class);
        } catch (Exception e) {
            log.error("unparsable record on {}-{} at offset {}: {}",
                    topic, record.partition(), record.offset(), e.getMessage());
            metrics.payloadInvalid(topic, "unparsable");
            return Optional.empty();
        }
        if (!event.hasRequiredAttributes()) {
            metrics.payloadInvalid(topic, String.valueOf(event.type()));
            return Optional.empty();
        }
        Optional<CatalogEventType> type = CatalogEventType.from(event.type());
        if (type.isEmpty()) {
            metrics.eventIgnored(topic, event.type());
            return Optional.empty();
        }
        return Optional.of(new TypedEvent(event, type.get()));
    }
}
