package com.ecommerce.search.worker.handler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.ecommerce.search.common.SearchMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Sends one batch of projection writes and classifies each item's outcome.
 *
 * <p>The classification is what replaces a dead-letter queue (ADR-005): a transient
 * failure leaves the offset uncommitted so Kafka redelivers, a missing document is a
 * normal no-op, and a non-recoverable data error is logged, counted and acknowledged.
 */
@Component
public class BatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutor.class);

    private final ElasticsearchClient client;
    private final SearchMetrics metrics;

    public BatchExecutor(ElasticsearchClient client, SearchMetrics metrics) {
        this.client = client;
        this.metrics = metrics;
    }

    /**
     * @return true when the offset may be committed — every record either applied or was
     *         classified as non-recoverable
     */
    public boolean execute(String topic, EventBatch batch) {
        if (batch.isEmpty()) {
            return !batch.hasRetryableFailure();
        }
        try {
            BulkResponse response = client.bulk(BulkRequest.of(builder -> builder.operations(batch.operations())));
            boolean commit = !batch.hasRetryableFailure();
            for (BulkResponseItem item : response.items()) {
                if (!classify(topic, item)) {
                    commit = false;
                }
            }
            return commit;
        } catch (IOException e) {
            // Elasticsearch is unreachable or timed out: retry belongs to Kafka.
            log.warn("bulk write for {} failed transiently, offset will not be committed: {}",
                    topic, e.getMessage());
            metrics.indexing(topic, "batch", "retry");
            return false;
        }
    }

    /** @return false when the item must cause a redelivery */
    private boolean classify(String topic, BulkResponseItem item) {
        if (item.error() == null) {
            if ("noop".equals(item.result())) {
                // The canonical scripts signal both a stale revision and a tombstoned
                // document with ctx.op = 'none', so the bulk response cannot tell the two
                // apart; both are counted here as a guard block.
                metrics.indexing(topic, "batch", "guard_blocked");
            }
            return true;
        }
        String errorType = String.valueOf(item.error().type());
        if (errorType.contains("document_missing")) {
            // Stock and review writes do not upsert; the SKU is simply not indexed yet or
            // has just been deleted.
            metrics.documentMissing(topic, "batch");
            return true;
        }
        if (item.status() == 429 || item.status() == 503) {
            metrics.indexing(topic, "batch", "retry");
            return false;
        }
        log.error("non-recoverable write error on {} for document {}: {} {}",
                topic, item.id(), errorType, item.error().reason());
        metrics.payloadInvalid(topic, "batch");
        metrics.indexing(topic, "batch", "fail");
        return true;
    }
}
