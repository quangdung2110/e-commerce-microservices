package com.ecommerce.search.worker.handler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.worker.client.InventoryReadinessClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends one batch of projection writes, classifies each item's outcome, and settles the
 * readiness calls the landed writes owe Inventory.
 *
 * <p>The classification is what replaces a dead-letter queue (ADR-005). A transient failure
 * leaves the offset uncommitted so Kafka redelivers; a non-recoverable data error is logged,
 * counted and acknowledged. A missing document is normally the first of those two — but not
 * for Inventory availability, where readiness has already guaranteed the document exists, so
 * a miss is a contract violation that must be redelivered and paged rather than swallowed
 * (ADR-019).
 */
@Component
public class BatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutor.class);

    private final ElasticsearchClient client;
    private final InventoryReadinessClient readiness;
    private final SearchMetrics metrics;

    public BatchExecutor(ElasticsearchClient client, InventoryReadinessClient readiness, SearchMetrics metrics) {
        this.client = client;
        this.readiness = readiness;
        this.metrics = metrics;
    }

    /**
     * @return true when the offset may be committed — every record either applied, was
     *         classified as non-recoverable, and every readiness call it owed succeeded
     */
    public boolean execute(String topic, EventBatch batch) {
        if (batch.isEmpty()) {
            return !batch.hasRetryableFailure();
        }
        try {
            BulkResponse response = client.bulk(BulkRequest.of(builder -> builder.operations(batch.operations())));
            boolean commit = !batch.hasRetryableFailure();
            List<EventBatch.ReadinessIntent> owed = new ArrayList<>();

            List<BulkResponseItem> items = response.items();
            for (int i = 0; i < items.size(); i++) {
                BulkResponseItem item = items.get(i);
                EventBatch.Entry entry = batch.entryAt(i);
                if (!classify(topic, item, entry)) {
                    commit = false;
                } else if (entry.readiness() != null && landed(item)) {
                    owed.add(entry.readiness());
                }
            }
            // Always settled, even when the batch is already going to be redelivered: the
            // writes that did land have to be registered, and the calls are idempotent.
            boolean readinessSettled = settleReadiness(owed);
            return commit && readinessSettled;
        } catch (IOException e) {
            // Elasticsearch is unreachable or timed out: retry belongs to Kafka.
            log.warn("bulk write for {} failed transiently, offset will not be committed: {}",
                    topic, e.getMessage());
            metrics.indexing(topic, "batch", "retry");
            return false;
        }
    }

    /**
     * A write only owes Inventory a readiness call when it actually changed the document.
     * A {@code noop} means a guard blocked it: either the document is tombstoned, in which
     * case registering would be wrong, or a newer revision already won and already carried
     * its own readiness call.
     */
    private static boolean landed(BulkResponseItem item) {
        return item.error() == null && !"noop".equals(item.result());
    }

    /**
     * Registers and revokes in order, stopping at the first failure so a redelivery replays
     * the same sequence. A conflict is not a failure to retry — nothing about it changes on
     * redelivery — so it is paged and acknowledged.
     *
     * @return true when the offset may be committed
     */
    private boolean settleReadiness(List<EventBatch.ReadinessIntent> owed) {
        for (EventBatch.ReadinessIntent intent : owed) {
            InventoryReadinessClient.Outcome outcome = switch (intent.operation()) {
                case REGISTER -> readiness.register(intent.skuId(), intent.catalogRevision());
                case REVOKE -> readiness.revoke(intent.skuId(), intent.catalogRevision());
            };
            if (outcome == InventoryReadinessClient.Outcome.FAILED) {
                return false;
            }
        }
        return true;
    }

    /** @return false when the item must cause a redelivery */
    private boolean classify(String topic, BulkResponseItem item, EventBatch.Entry entry) {
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
            metrics.documentMissing(topic, "batch");
            if (entry.missingDocument() == EventBatch.MissingDocument.CONTRACT_VIOLATION) {
                log.error("availability write for document {} on {} found no document; readiness was "
                        + "registered before inventory published, so this is a contract violation: "
                        + "not acknowledging and not upserting", item.id(), topic);
                metrics.indexing(topic, "batch", "readiness_violation");
                return false;
            }
            // A catalog or category tombstone may find nothing: the SKU was never indexed
            // or has already been purged.
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
