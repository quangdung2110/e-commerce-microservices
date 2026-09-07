package com.ecommerce.search.worker.handler;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import com.ecommerce.search.common.SearchMetrics;
import com.ecommerce.search.worker.client.InventoryReadinessClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How one bulk response decides whether the offset moves.
 *
 * <p>The guard behaviour of the scripts is proven against a real Elasticsearch in
 * {@code CatalogProjectionIT}; what needs isolating here is the readiness settlement, since
 * it depends on outcomes an integration test cannot produce on demand.
 */
class BatchExecutorTest {

    private static final String INDEX = "products_write";
    private static final String TOPIC = "t.product.events";

    private ElasticsearchClient client;
    private InventoryReadinessClient readiness;
    private BatchExecutor executor;

    @BeforeEach
    void setUp() {
        client = mock(ElasticsearchClient.class);
        readiness = mock(InventoryReadinessClient.class);
        when(readiness.register(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.OK);
        when(readiness.revoke(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.OK);
        executor = new BatchExecutor(client, readiness, new SearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void registersReadinessOnlyAfterTheCatalogWriteLands() throws IOException {
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.register("sku-1", 7L));
        respondWith(applied("sku-1"));

        assertThat(executor.execute(TOPIC, batch)).isTrue();
        verify(readiness).register("sku-1", 7L);
    }

    @Test
    void doesNotRegisterReadinessForAGuardBlockedWrite() throws IOException {
        // A noop is either a stale revision — the newer event already registered — or a
        // tombstoned document, which must never be registered at all.
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.register("sku-1", 7L));
        respondWith(noop("sku-1"));

        assertThat(executor.execute(TOPIC, batch)).isTrue();
        verify(readiness, never()).register(anyString(), anyLong());
    }

    @Test
    void holdsTheOffsetWhenReadinessRegistrationFails() throws IOException {
        when(readiness.register(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.FAILED);
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.register("sku-1", 7L));
        respondWith(applied("sku-1"));

        assertThat(executor.execute(TOPIC, batch)).isFalse();
    }

    @Test
    void revokesReadinessWhenATombstoneLands() throws IOException {
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.revoke("sku-1", 9L));
        respondWith(applied("sku-1"));

        assertThat(executor.execute(TOPIC, batch)).isTrue();
        verify(readiness).revoke("sku-1", 9L);
    }

    @Test
    void commitsWhenARevokeConflictsBecauseRedeliveryWouldChangeNothing() throws IOException {
        when(readiness.revoke(anyString(), anyLong())).thenReturn(InventoryReadinessClient.Outcome.CONFLICT);
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.revoke("sku-1", 9L));
        respondWith(applied("sku-1"));

        assertThat(executor.execute(TOPIC, batch)).isTrue();
    }

    @Test
    void doesNotAcknowledgeAnAvailabilityWriteThatFoundNoDocument() throws IOException {
        // SEARCH-INV-005 under ADR-019: readiness has already guaranteed the document, so
        // this is a contract violation to page on, not a race to swallow.
        EventBatch batch = new EventBatch(INDEX);
        batch.addAvailabilityWrite(operation("sku-1"));
        respondWith(documentMissing("sku-1"));

        assertThat(executor.execute("t.inventory.stock.events.v1", batch)).isFalse();
    }

    @Test
    void acknowledgesACatalogTombstoneThatFoundNoDocument() throws IOException {
        // The SKU was never indexed, or was purged after its protection window.
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.revoke("sku-1", 9L));
        respondWith(documentMissing("sku-1"));

        assertThat(executor.execute(TOPIC, batch)).isTrue();
        verify(readiness, never()).revoke(anyString(), anyLong());
    }

    @Test
    void settlesReadinessForTheWritesThatLandedEvenWhenTheBatchWillBeRedelivered() throws IOException {
        EventBatch batch = new EventBatch(INDEX);
        batch.addCatalogWrite(operation("sku-1"), EventBatch.ReadinessIntent.register("sku-1", 7L));
        batch.addCatalogWrite(operation("sku-2"), EventBatch.ReadinessIntent.register("sku-2", 7L));
        respondWith(applied("sku-1"), rejected("sku-2", 429, "es_rejected_execution_exception"));

        assertThat(executor.execute(TOPIC, batch)).isFalse();
        verify(readiness).register("sku-1", 7L);
        verify(readiness, never()).register("sku-2", 7L);
    }

    // ------------------------------------------------------------------ fixtures

    private void respondWith(BulkResponseItem... items) throws IOException {
        BulkResponse response = BulkResponse.of(builder -> builder
                .took(5).errors(false).items(List.of(items)));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);
    }

    private static BulkOperation operation(String id) {
        return BulkOperation.of(op -> op.delete(delete -> delete.index(INDEX).id(id)));
    }

    private static BulkResponseItem applied(String id) {
        return item(id, 200, "updated", null);
    }

    private static BulkResponseItem noop(String id) {
        return item(id, 200, "noop", null);
    }

    private static BulkResponseItem documentMissing(String id) {
        return item(id, 404, null, ErrorCause.of(cause -> cause
                .type("document_missing_exception").reason("[" + id + "]: document missing")));
    }

    private static BulkResponseItem rejected(String id, int status, String type) {
        return item(id, status, null, ErrorCause.of(cause -> cause.type(type).reason("rejected")));
    }

    private static BulkResponseItem item(String id, int status, String result, ErrorCause error) {
        return BulkResponseItem.of(builder -> {
            builder.operationType(OperationType.Update).index(INDEX).id(id).status(status);
            if (result != null) {
                builder.result(result);
            }
            if (error != null) {
                builder.error(error);
            }
            return builder;
        });
    }
}
