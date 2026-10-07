package com.ecommerce.search.worker.job;

import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.config.SearchProperties;
import com.ecommerce.search.domain.event.CatalogEventType;
import com.ecommerce.search.domain.event.CloudEventEnvelope;
import com.ecommerce.search.worker.client.ExportPages;
import com.ecommerce.search.worker.handler.AvailabilityEventHandler;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogEventHandler;
import com.ecommerce.search.worker.handler.CategoryEnrichment;
import com.ecommerce.search.worker.handler.CategoryEventHandler;
import com.ecommerce.search.worker.handler.EventBatch;
import com.ecommerce.search.worker.handler.ReviewEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catch-up phase of a full reindex ({@code WF-SEARCH-009} step 4).
 *
 * <p>A throwaway consumer group {@code search-reindex-{run_id}} seeks inclusively to the
 * offsets each source captured before its scan, and writes <strong>directly into the new
 * versioned index</strong> — the single documented exception to writing through the write
 * alias. Guards are unchanged, so an event older than the snapshot is a no-op.
 *
 * <p>The phase ends only when lag has been zero for several consecutive polls; if it never
 * converges within the deadline the run fails and the aliases stay where they are.
 */
@Component
public class ReindexCatchUp {

    private static final Logger log = LoggerFactory.getLogger(ReindexCatchUp.class);

    private final KafkaProperties kafkaProperties;
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;
    private final BatchExecutor executor;
    private final CatalogEventHandler catalogHandler;
    private final CategoryEventHandler categoryHandler;
    private final AvailabilityEventHandler availabilityHandler;
    private final ReviewEventHandler reviewHandler;
    private final CategoryEnrichment enrichment;
    private final SearchMetrics metrics;

    public ReindexCatchUp(KafkaProperties kafkaProperties,
                          SearchProperties properties,
                          ObjectMapper esObjectMapper,
                          BatchExecutor executor,
                          CatalogEventHandler catalogHandler,
                          CategoryEventHandler categoryHandler,
                          AvailabilityEventHandler availabilityHandler,
                          ReviewEventHandler reviewHandler,
                          CategoryEnrichment enrichment,
                          SearchMetrics metrics) {
        this.kafkaProperties = kafkaProperties;
        this.properties = properties;
        this.objectMapper = esObjectMapper;
        this.executor = executor;
        this.catalogHandler = catalogHandler;
        this.categoryHandler = categoryHandler;
        this.availabilityHandler = availabilityHandler;
        this.reviewHandler = reviewHandler;
        this.enrichment = enrichment;
        this.metrics = metrics;
    }

    public void replay(String runId, String productsIndex, String categoriesIndex,
                       ReindexProgressState progress, Instant runDeadline) {
        List<ExportPages.ReplayOffset> offsets = progress.allOffsets();
        if (offsets.isEmpty()) {
            throw new IllegalStateException("catch-up has no replay offsets to seek to");
        }

        Instant deadline = earliest(runDeadline, Instant.now().plus(properties.reindex().catchUpDeadline()));
        int zeroLagPolls = 0;

        try (KafkaConsumer<String, String> consumer = createConsumer(runId)) {
            Map<TopicPartition, Long> startAt = new HashMap<>();
            offsets.forEach(offset -> startAt.merge(
                    new TopicPartition(offset.topic(), offset.partition()), offset.offset(), Math::min));

            consumer.assign(startAt.keySet());
            // Offsets are inclusive: the record at that offset is the first to replay.
            startAt.forEach(consumer::seek);
            log.info("catch-up for run {} replaying from {}", runId, startAt);

            while (zeroLagPolls < properties.reindex().catchUpZeroLagPolls()) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("catch-up did not converge before its deadline");
                }
                ConsumerRecords<String, String> records =
                        consumer.poll(properties.reindex().catchUpPollTimeout());
                if (records.isEmpty()) {
                    zeroLagPolls++;
                    progress.setCatchUpLag(0L);
                    continue;
                }
                zeroLagPolls = 0;
                progress.setCatchUpLag((long) records.count());
                apply(records, productsIndex, categoriesIndex);
            }
            progress.setCatchUpLag(0L);
            log.info("catch-up for run {} converged", runId);
        }
    }

    /** Applies one poll, routing each record to the handler that owns its topic. */
    private void apply(ConsumerRecords<String, String> records, String productsIndex, String categoriesIndex) {
        EventBatch productBatch = new EventBatch(productsIndex);
        EventBatch categoryBatch = new EventBatch(categoriesIndex);
        CategoryEnrichment.Cache cache = enrichment.newCache();
        List<CloudEventEnvelope> ratingEvents = new ArrayList<>();

        for (ConsumerRecord<String, String> record : records) {
            Optional<CloudEventEnvelope> parsed = parse(record);
            if (parsed.isEmpty()) {
                continue;
            }
            CloudEventEnvelope event = parsed.get();
            Optional<CatalogEventType> type = CatalogEventType.from(event.type());
            if (type.isEmpty()) {
                metrics.eventIgnored(record.topic(), event.type());
                continue;
            }
            String topic = record.topic();
            switch (type.get()) {
                case SKU_UPSERTED, SKU_DELETED, PRODUCT_DELETED ->
                        catalogHandler.handle(topic, event, type.get(), productBatch, cache);
                case CATEGORY_UPSERTED, CATEGORY_DELETED ->
                        categoryHandler.handle(topic, event, type.get(), categoryBatch);
                case SKU_AVAILABILITY_CHANGED -> availabilityHandler.handle(topic, event, productBatch);
                case REVIEW_METRICS_UPDATED -> ratingEvents.add(event);
                case CATALOG_REINDEX_REQUESTED -> {
                    // Handled by the control coordinator; the run is superseded elsewhere.
                }
            }
        }

        executor.execute("reindex-catch-up", categoryBatch);
        executor.execute("reindex-catch-up", productBatch);
        ratingEvents.forEach(event ->
                reviewHandler.handle(properties.topics().review(), event, productsIndex));
    }

    private Optional<CloudEventEnvelope> parse(ConsumerRecord<String, String> record) {
        try {
            CloudEventEnvelope event = objectMapper.readValue(record.value(), CloudEventEnvelope.class);
            return event.hasRequiredAttributes() ? Optional.of(event) : Optional.empty();
        } catch (Exception e) {
            metrics.payloadInvalid(record.topic(), "unparsable");
            return Optional.empty();
        }
    }

    /**
     * A throwaway group that never commits: the run's own offsets state is the checkpoint,
     * and leaving a committed group behind would confuse the main indexer's monitoring.
     */
    private KafkaConsumer<String, String> createConsumer(String runId) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "search-reindex-" + runId);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    /** Exposed for tests that need the poll timeout without reaching into properties. */
    Duration pollTimeout() {
        return properties.reindex().catchUpPollTimeout();
    }
}
