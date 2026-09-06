package com.ecommerce.search.worker.handler;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * Write operations produced by one Kafka poll, plus the target index.
 *
 * <p>The target is a parameter because the catch-up phase of a reindex writes
 * <em>directly</em> into the new versioned index instead of the write alias — the only
 * exception to {@code SEARCH-INV-007}.
 */
public final class EventBatch {

    private final String targetIndex;
    private final List<BulkOperation> operations = new ArrayList<>();
    private boolean retryableFailure;

    public EventBatch(String targetIndex) {
        this.targetIndex = targetIndex;
    }

    public String targetIndex() {
        return targetIndex;
    }

    public void add(BulkOperation operation) {
        operations.add(operation);
    }

    public List<BulkOperation> operations() {
        return List.copyOf(operations);
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * Marks that at least one record hit a transient failure. The consumer must then leave
     * the offset uncommitted so Kafka redelivers the batch — there is no DLQ (ADR-005).
     */
    public void markRetryableFailure() {
        this.retryableFailure = true;
    }

    public boolean hasRetryableFailure() {
        return retryableFailure;
    }
}
