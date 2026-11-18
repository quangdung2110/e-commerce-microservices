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
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catch-up phase of a full reindex ({@code WF-SEARCH-009} step 4), and the tail replay that
 * follows the cutover.
 *
 * <p>A throwaway consumer group {@code search-reindex-{run_id}} seeks inclusively to the
 * offsets each source captured before its scan, and writes <strong>directly into the new
 * versioned index</strong> — the single documented exception to writing through the write
 * alias. Guards are unchanged, so an event older than the snapshot is a no-op.
 *
 * <p>The phase ends only when lag has been zero for several consecutive polls; if it never
 * converges within the deadline the run fails and the aliases stay where they are. A batch the
 * index could not take is applied again after a backoff, and the run fails if it never lands:
 * dropping it would let the run cut over an index that is missing events.
 *
 * <p>The live indexer keeps writing into the index that is still serving until the aliases
 * move, so whatever it applies between the end of catch-up and the swap never reaches the new
 * index. {@link #replayTail} reads that stretch once more after the swap.
 */
@Component
public class ReindexCatchUp {

    private static final Logger log = LoggerFactory.getLogger(ReindexCatchUp.class);

    /** Empty polls that end a tail replay; it only has to cover seconds, not a backlog. */
    private static final int TAIL_ZERO_LAG_POLLS = 2;

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

    /**
     * Replays every source from its recorded offset until lag stays at zero, then records where
     * each partition ended so a tail replay can start there.
     *
     * @param checkpoint called between polls; throws to stop the run (cancellation, lost lease)
     */
    public void replay(String runId, String productsIndex, String categoriesIndex,
                       ReindexProgressState progress, Instant runDeadline, Runnable checkpoint) {
        List<ExportPages.ReplayOffset> offsets = progress.allOffsets();
        if (offsets.isEmpty()) {
            throw new IllegalStateException("catch-up has no replay offsets to seek to");
        }
        Map<TopicPartition, Long> startAt = new HashMap<>();
        offsets.forEach(offset -> startAt.merge(
                new TopicPartition(offset.topic(), offset.partition()), offset.offset(), Math::min));

        Instant deadline = earliest(runDeadline, Instant.now().plus(properties.reindex().catchUpDeadline()));
        Map<String, Long> ended = consume(runId, startAt, productsIndex, categoriesIndex, progress, deadline,
                properties.reindex().catchUpZeroLagPolls(), checkpoint, true);
        progress.recordCatchUpEnd(ended, Instant.now());
        progress.setCatchUpLag(0L);
        log.info("catch-up for run {} converged at {}", runId, ended);
    }

    /**
     * Reads, once more and into the index that now serves, everything the topics carried since
     * catch-up converged. Replaying a record the live indexer already applied is a no-op
     * because every write is guarded by a revision.
     *
     * @return false when the tail could not be read completely; the run stays completed and the
     *         affected documents heal on their next event or the next reindex
     */
    public boolean replayTail(String runId, String productsIndex, String categoriesIndex,
                              ReindexProgressState progress, Runnable checkpoint) {
        Map<String, Long> ended = progress.catchUpEndPositions();
        if (ended.isEmpty()) {
            return true;
        }
        Map<TopicPartition, Long> startAt = new HashMap<>();
        ended.forEach((key, offset) -> startAt.put(parse(key), offset));
        try {
            consume(runId + "-tail", startAt, productsIndex, categoriesIndex, progress,
                    Instant.now().plus(properties.reindex().catchUpDeadline()), TAIL_ZERO_LAG_POLLS,
                    checkpoint, false);
            log.info("tail replay for run {} finished", runId);
            return true;
        } catch (RuntimeException e) {
            log.error("tail replay for run {} did not finish; documents changed between catch-up and the "
                    + "cutover keep their older state until their next event: {}", runId, e.getMessage());
            return false;
        }
    }

    private Map<String, Long> consume(String groupSuffix, Map<TopicPartition, Long> startAt,
                                      String productsIndex, String categoriesIndex,
                                      ReindexProgressState progress, Instant deadline, int zeroLagPollsNeeded,
                                      Runnable checkpoint, boolean reportLag) {
        int zeroLagPolls = 0;
        int failedAttempts = 0;
        Duration backoff = properties.bulk().initialBackoff();

        try (KafkaConsumer<String, String> consumer = createConsumer(groupSuffix)) {
            consumer.assign(startAt.keySet());
            // Offsets are inclusive: the record at that offset is the first to replay.
            startAt.forEach(consumer::seek);
            log.info("catch-up consumer {} replaying from {}", groupSuffix, startAt);

            while (zeroLagPolls < zeroLagPollsNeeded) {
                checkpoint.run();
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("catch-up did not converge before its deadline");
                }
                ConsumerRecords<String, String> records =
                        consumer.poll(properties.reindex().catchUpPollTimeout());
                if (records.isEmpty()) {
                    zeroLagPolls++;
                    if (reportLag) {
                        progress.setCatchUpLag(0L);
                    }
                    continue;
                }
                zeroLagPolls = 0;
                if (reportLag) {
                    progress.setCatchUpLag((long) records.count());
                }
                if (apply(records, productsIndex, categoriesIndex)) {
                    failedAttempts = 0;
                    backoff = properties.bulk().initialBackoff();
                    continue;
                }
                // The index could not take the batch. Read it again rather than move on:
                // every record is guarded, so the part that did land is a no-op the second time.
                failedAttempts++;
                if (failedAttempts > properties.bulk().maxRetries()) {
                    throw new IllegalStateException("catch-up could not apply a batch after "
                            + properties.bulk().maxRetries() + " retries; refusing to continue without its events");
                }
                rewind(consumer, records);
                metrics.indexing("reindex-catch-up", "batch", "redelivery");
                sleep(backoff);
                backoff = backoff.multipliedBy(2).compareTo(properties.bulk().maxBackoff()) > 0
                        ? properties.bulk().maxBackoff() : backoff.multipliedBy(2);
            }
            Map<String, Long> ended = new HashMap<>();
            for (TopicPartition partition : startAt.keySet()) {
                ended.put(key(partition), consumer.position(partition));
            }
            return ended;
        }
    }

    /** Seeks every partition of the poll back to its first record. */
    private static void rewind(KafkaConsumer<String, String> consumer, ConsumerRecords<String, String> records) {
        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<String, String>> batch = records.records(partition);
            if (!batch.isEmpty()) {
                consumer.seek(partition, batch.get(0).offset());
            }
        }
    }

    /**
     * Applies one poll, routing each record to the handler that owns its topic.
     *
     * @return true when every write landed or was classified as not worth retrying
     */
    private boolean apply(ConsumerRecords<String, String> records, String productsIndex, String categoriesIndex) {
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

        boolean applied = executor.execute("reindex-catch-up", categoryBatch);
        applied &= executor.execute("reindex-catch-up", productBatch);
        for (CloudEventEnvelope event : ratingEvents) {
            applied &= reviewHandler.handle(properties.topics().review(), event, productsIndex);
        }
        return applied;
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
    private KafkaConsumer<String, String> createConsumer(String groupSuffix) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "search-reindex-" + groupSuffix);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }

    private static String key(TopicPartition partition) {
        return partition.topic() + ":" + partition.partition();
    }

    private static TopicPartition parse(String key) {
        int split = key.lastIndexOf(':');
        return new TopicPartition(key.substring(0, split), Integer.parseInt(key.substring(split + 1)));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("catch-up was interrupted", e);
        }
    }

    private static Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    /** Exposed for tests that need the poll timeout without reaching into properties. */
    Duration pollTimeout() {
        return properties.reindex().catchUpPollTimeout();
    }
}
