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
 *
 * <p>Each operation carries what the bulk response alone cannot say: whether a
 * {@code document_missing} on it is tolerable, and whether a readiness call owes Inventory
 * a follow-up once the write lands. Both are decided by the source that produced the write,
 * so they are recorded here rather than guessed from the topic name — the reindex catch-up
 * mixes sources in one batch under a synthetic topic.
 */
public final class EventBatch {

    private final String targetIndex;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> missingDocumentIds = new ArrayList<>();
    private final List<String> settledDocumentIds = new ArrayList<>();
    private boolean retryableFailure;

    public EventBatch(String targetIndex) {
        this.targetIndex = targetIndex;
    }

    public String targetIndex() {
        return targetIndex;
    }

    /** A write whose target may legitimately be absent and that owes Inventory nothing. */
    public void add(BulkOperation operation) {
        entries.add(new Entry(operation, MissingDocument.TOLERATED, null));
    }

    /**
     * A catalog write. A tombstone may find no document — the SKU was never indexed or was
     * already purged — so a miss is tolerated; the readiness call follows only if the write
     * actually landed.
     */
    public void addCatalogWrite(BulkOperation operation, ReadinessIntent readiness) {
        entries.add(new Entry(operation, MissingDocument.TOLERATED, readiness));
    }

    /**
     * An Inventory availability write. Readiness guarantees the document exists, so a miss
     * here is a contract violation: it must not be acknowledged ({@code SEARCH-INV-005},
     * ADR-019).
     */
    public void addAvailabilityWrite(BulkOperation operation) {
        entries.add(new Entry(operation, MissingDocument.CONTRACT_VIOLATION, null));
    }

    public List<BulkOperation> operations() {
        return entries.stream().map(Entry::operation).toList();
    }

    /** The entry behind the bulk response item at the same position. */
    public Entry entryAt(int index) {
        return entries.get(index);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
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

    /**
     * Records how one availability write ended, keyed by the document it targeted.
     *
     * <p>Only a reindex reads these. Live indexing already has its answer in the commit
     * decision, but a build has to know <em>which</em> SKUs are still waiting for Product to
     * create their document, because that set is what blocks cutover ({@code WF-SEARCH-009}).
     */
    void recordAvailabilityOutcome(String documentId, boolean documentMissing) {
        if (documentId == null) {
            return;
        }
        if (documentMissing) {
            missingDocumentIds.add(documentId);
        } else {
            settledDocumentIds.add(documentId);
        }
    }

    /** Availability targets that did not exist. */
    public List<String> missingDocumentIds() {
        return List.copyOf(missingDocumentIds);
    }

    /** Availability targets that did exist, whatever the guard then decided. */
    public List<String> settledDocumentIds() {
        return List.copyOf(settledDocumentIds);
    }

    public record Entry(BulkOperation operation, MissingDocument missingDocument, ReadinessIntent readiness) {
    }

    /** How to classify {@code document_missing_exception} for one write. */
    public enum MissingDocument {
        TOLERATED,
        CONTRACT_VIOLATION
    }

    /** A readiness call owed to Inventory once the write it belongs to has landed. */
    public record ReadinessIntent(Operation operation, String skuId, long catalogRevision) {

        public static ReadinessIntent register(String skuId, long catalogRevision) {
            return new ReadinessIntent(Operation.REGISTER, skuId, catalogRevision);
        }

        public static ReadinessIntent revoke(String skuId, long catalogRevision) {
            return new ReadinessIntent(Operation.REVOKE, skuId, catalogRevision);
        }

        public enum Operation {
            REGISTER,
            REVOKE
        }
    }
}
