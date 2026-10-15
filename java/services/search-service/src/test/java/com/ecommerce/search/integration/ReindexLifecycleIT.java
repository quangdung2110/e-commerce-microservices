package com.ecommerce.search.integration;

import com.ecommerce.search.common.Timestamps;
import com.ecommerce.search.domain.document.CategoryDocument;
import com.ecommerce.search.domain.document.LeaseLockDocument;
import com.ecommerce.search.domain.document.ProductDocument;
import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.domain.event.CatalogPayloads;
import com.ecommerce.search.repository.JobStateRepository;
import com.ecommerce.search.repository.es.IndexAdmin;
import com.ecommerce.search.repository.es.IndexTemplates;
import com.ecommerce.search.repository.es.ProjectionWriter;
import com.ecommerce.search.worker.handler.BatchExecutor;
import com.ecommerce.search.worker.handler.CatalogProjection;
import com.ecommerce.search.worker.handler.EventBatch;
import com.ecommerce.search.worker.job.IndexCleanupJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The full reindex against a real Elasticsearch and Kafka, with Product, Inventory and Review
 * played by {@link ExportStubServer}: the whole pipeline and the operations around it —
 * verified atomic cutover, rollback, cancellation, resume of an abandoned run, abandonment
 * after too many resumes, the automatic run for a category tree change, and index cleanup.
 */
@TestPropertySource(properties = {
        "search.reindex.catch-up-zero-lag-polls=1",
        "search.reindex.catch-up-poll-timeout=1s",
        "search.reindex.catch-up-deadline=2m",
        "search.lifecycle.lease-duration=6s",
        "search.lifecycle.lease-renew-interval=2s",
        "search.lifecycle.watchdog-interval=1s",
        "search.lifecycle.control-debounce=2s",
        "search.lifecycle.cleanup-enabled=false",
        "search.verify.hot-queries[0]=iphone"
})
class ReindexLifecycleIT extends AbstractSearchIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(150);
    private static final ExportStubServer STUB;

    static {
        try {
            STUB = new ExportStubServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void stubProperties(DynamicPropertyRegistry registry) {
        registry.add("search.export.product-base-url", STUB::baseUrl);
        registry.add("search.export.inventory-base-url", STUB::baseUrl);
        registry.add("search.export.review-base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JobStateRepository runs;
    @Autowired
    private IndexAdmin indexAdmin;
    @Autowired
    private ProjectionWriter writer;
    @Autowired
    private BatchExecutor executor;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private IndexCleanupJob cleanup;

    @BeforeEach
    void resetJobState() throws Exception {
        // A previous test's run must be completely over (including the tail replay that still
        // holds the lease) before the state it left is wiped.
        awaitNoLiveLease();
        client.deleteByQuery(builder -> builder
                .index(properties.aliases().searchJobs())
                .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)
                .refresh(true)
                .query(query -> query.matchAll(all -> all)));
        STUB.reset();
        // The catch-up consumer assigns partitions directly, so they have to exist.
        for (String topic : List.of(properties.topics().product(), properties.topics().category(),
                properties.topics().review(), properties.topics().stock(), properties.topics().catalogControl())) {
            kafka.send(topic, "bootstrap", "{}").get();
        }
    }

    // ------------------------------------------------------------------ full pipeline

    @Test
    void aFullReindexBuildsVerifiesAndCutsOverAtomicallyAndCanBeRolledBack() throws Exception {
        seedCurrentIndex(1L, 28_000_000L);
        String oldProducts = indexAdmin.resolveAlias(properties.aliases().products()).orElseThrow();
        String oldCategories = indexAdmin.resolveAlias(properties.aliases().categories()).orElseThrow();

        ResponseEntity<String> started = post("/api/v2/search/reindex-runs", "{\"reason\":\"integration test\"}");
        assertThat(started.getStatusCode().value()).isEqualTo(202);
        String runId = JSON.readTree(started.getBody()).path("run_id").asText();

        ReindexRunDocument done = awaitStatus(runId, "COMPLETED");
        String newProducts = done.targetIndex();

        // Both alias pairs moved, to indices of the same generation.
        assertThat(newProducts).isNotEqualTo(oldProducts);
        assertThat(indexAdmin.resolveAlias(properties.aliases().products())).contains(newProducts);
        assertThat(indexAdmin.resolveAlias(properties.aliases().productsWrite())).contains(newProducts);
        String newCategories = (String) done.checkpointOrEmpty().get("categories_index");
        assertThat(indexAdmin.resolveAlias(properties.aliases().categories())).contains(newCategories);
        assertThat(indexAdmin.resolveAlias(properties.aliases().categoriesWrite())).contains(newCategories);

        // Every snapshot made it into the new index and went through the guards.
        ProductDocument sku = read(newProducts, ExportStubServer.SKU_ID).orElseThrow();
        assertThat(sku.inStock()).isTrue();
        assertThat(sku.availabilityRevision()).isEqualTo(1L);
        assertThat(sku.productRatingAvg()).isEqualTo(4.5);
        assertThat(sku.reviewRevision()).isEqualTo(3L);
        assertThat(sku.catalogRevision()).isEqualTo(1L);
        assertThat(client.get(g -> g.index(newCategories).id(ExportStubServer.CATEGORY_ID),
                CategoryDocument.class).found()).isTrue();

        // The way back is written down and the replaced indices start their retention clock.
        assertThat(done.checkpointOrEmpty()).containsEntry("previous_products_index", oldProducts)
                .containsEntry("previous_categories_index", oldCategories);
        assertThat(runs.retiredIndices()).containsKeys(oldProducts, oldCategories);

        // The run is listed.
        JsonNode list = JSON.readTree(get("/api/v2/search/reindex-runs?status=COMPLETED").getBody());
        assertThat(list.path("items").findValuesAsText("run_id")).contains(runId);
        assertThat(list.path("items").get(0).path("reason").asText()).isEqualTo("integration test");
        assertThat(list.path("items").get(0).path("actor").asText()).isEqualTo("admin-1");

        // The tail replay still holds the lease; rollback waits for the run to be completely over.
        awaitNoLiveLease();
        // Rollback returns the replaced indices behind the same four aliases.
        ResponseEntity<String> rolledBack = post("/api/v2/search/reindex-runs/" + runId + "/rollback", null);
        assertThat(rolledBack.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(rolledBack.getBody()).path("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(indexAdmin.resolveAlias(properties.aliases().products())).contains(oldProducts);
        assertThat(indexAdmin.resolveAlias(properties.aliases().productsWrite())).contains(oldProducts);
        assertThat(indexAdmin.resolveAlias(properties.aliases().categories())).contains(oldCategories);
        assertThat(runs.retiredIndices()).containsKey(newProducts).doesNotContainKeys(oldProducts, oldCategories);

        // A run that is no longer COMPLETED cannot be rolled back again.
        ResponseEntity<String> again = post("/api/v2/search/reindex-runs/" + runId + "/rollback", null);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(JSON.readTree(again.getBody()).path("code").asText()).isEqualTo("REINDEX_ROLLBACK_UNAVAILABLE");
    }

    @Test
    void aSecondTriggerWhileARunIsActiveIsRefused() throws Exception {
        STUB.holdCategoryExport();
        ResponseEntity<String> first = post("/api/v2/search/reindex-runs", "{\"reason\":\"first\"}");
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        String runId = JSON.readTree(first.getBody()).path("run_id").asText();
        assertThat(STUB.awaitCategoryExportEntered(30)).isTrue();

        ResponseEntity<String> second = post("/api/v2/search/reindex-runs", "{\"reason\":\"second\"}");

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(JSON.readTree(second.getBody()).path("code").asText()).isEqualTo("REINDEX_ALREADY_RUNNING");
        post("/api/v2/search/reindex-runs/" + runId + "/cancellation", null);
        STUB.releaseCategoryExport();
        awaitStatus(runId, "CANCELLED");
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancellingARunStopsItAndDeletesWhatItBuilt() throws Exception {
        STUB.holdCategoryExport();
        ResponseEntity<String> started = post("/api/v2/search/reindex-runs", "{\"reason\":\"to cancel\"}");
        String runId = JSON.readTree(started.getBody()).path("run_id").asText();
        assertThat(STUB.awaitCategoryExportEntered(30)).isTrue();
        ReindexRunDocument building = runs.findRun(runId).orElseThrow();
        String builtIndex = (String) building.checkpointOrEmpty().get("products_index");
        assertThat(builtIndex).isNotNull();
        assertThat(indexAdmin.indexExists(builtIndex)).isTrue();

        ResponseEntity<String> cancelled = post("/api/v2/search/reindex-runs/" + runId + "/cancellation", null);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(202);
        STUB.releaseCategoryExport();

        ReindexRunDocument done = awaitStatus(runId, "CANCELLED");
        assertThat(done.failureCode()).isEqualTo("RUN_CANCELLED");
        assertThat(indexAdmin.indexExists(builtIndex)).isFalse();
        // The aliases never moved, and the slot is free again.
        assertThat(runs.findActiveRun()).isEmpty();
        assertThat(rest.exchange("/api/v2/search/reindex-runs/" + runId + "/cancellation", HttpMethod.POST,
                new HttpEntity<>(adminHeaders()), String.class).getStatusCode().value()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ abandoned runs

    @Test
    void anAbandonedRunIsResumedFromItsSavedStage() throws Exception {
        String runId = saveAbandonedRun("BUILD_CATALOG", 0);

        ReindexRunDocument done = awaitStatus(runId, "COMPLETED");

        // The categories were not exported again, the catalog was, and one resume is counted.
        assertThat(STUB.categoryCalls.get()).isZero();
        assertThat(STUB.productCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) done.checkpointOrEmpty().get("resume_attempts")).intValue()).isEqualTo(1);
        assertThat(read(done.targetIndex(), ExportStubServer.SKU_ID)).isPresent();
    }

    @Test
    void aTriggerIsNotBlockedForeverByARunWhoseOwnerDied() throws Exception {
        String runId = saveAbandonedRun("BUILD_CATALOG", 0);

        ResponseEntity<String> trigger = post("/api/v2/search/reindex-runs", "{\"reason\":\"after a crash\"}");

        // The dead run is handled first (resumed), so this trigger is still refused once...
        assertThat(trigger.getStatusCode().value()).isEqualTo(409);
        awaitStatus(runId, "COMPLETED");
        awaitNoLiveLease();
        // ...but the slot is released afterwards.
        ResponseEntity<String> next = post("/api/v2/search/reindex-runs", "{\"reason\":\"next\"}");
        assertThat(next.getStatusCode().value()).isEqualTo(202);
        awaitStatus(JSON.readTree(next.getBody()).path("run_id").asText(), "COMPLETED");
    }

    @Test
    void aRunThatKeepsDyingIsFailedAsAbandonedAndItsIndicesAreDeleted() throws Exception {
        String runId = saveAbandonedRun("BUILD_CATALOG", 3);
        String builtIndex = (String) runs.findRun(runId).orElseThrow().checkpointOrEmpty().get("products_index");

        ReindexRunDocument done = awaitStatus(runId, "FAILED");

        assertThat(done.failureCode()).isEqualTo("RUN_ABANDONED");
        assertThat(indexAdmin.indexExists(builtIndex)).isFalse();
        assertThat(runs.findActiveRun()).isEmpty();
        assertThat(STUB.productCalls.get()).isZero();
    }

    // ------------------------------------------------------------------ verification

    @Test
    void anIndexThatWouldRegressTheCurrentOneIsNotCutOver() throws Exception {
        // The current index holds revision 5 of the SKU; the export only knows revision 1.
        seedCurrentIndex(5L, 28_000_000L);
        String currentProducts = indexAdmin.resolveAlias(properties.aliases().products()).orElseThrow();

        ResponseEntity<String> started = post("/api/v2/search/reindex-runs", "{\"reason\":\"stale export\"}");
        String runId = JSON.readTree(started.getBody()).path("run_id").asText();

        ReindexRunDocument done = awaitStatus(runId, "FAILED");

        assertThat(done.failureMessage()).contains("sample diff").contains("catalog_revision");
        assertThat(indexAdmin.resolveAlias(properties.aliases().products())).contains(currentProducts);
        assertThat(indexAdmin.indexExists(done.targetIndex())).isFalse();
    }

    // ------------------------------------------------------------------ control events

    @Test
    void aCategoryTreeChangeStartsOneRunAfterTheDebounceAndIsThenSatisfied() throws Exception {
        long revision = 7;
        kafka.send(properties.topics().catalogControl(), ExportStubServer.CATEGORY_ID,
                controlEvent(ExportStubServer.CATEGORY_ID, revision)).get();
        kafka.send(properties.topics().catalogControl(), ExportStubServer.CATEGORY_ID,
                controlEvent(ExportStubServer.CATEGORY_ID, revision + 1)).get();

        // Stored durably, coalesced to the highest revision of the root category.
        await().atMost(Duration.ofSeconds(30)).until(() -> runs.findReindexRequest().isPresent());
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            var roots = runs.findReindexRequest().map(r -> r.checkpoint().get("roots"));
            return roots.isPresent() && roots.get().toString().contains(String.valueOf(revision + 1));
        });

        // Then one automatic run, owned by the system actor, completes and clears the request.
        ReindexRunDocument automatic = await().atMost(RUN_TIMEOUT).pollInterval(Duration.ofSeconds(1))
                .until(() -> runs.listRuns(Optional.of("COMPLETED"), 5).stream().findFirst(), Optional::isPresent)
                .orElseThrow();
        assertThat(automatic.actor()).isEqualTo("system:catalog-control");
        assertThat(automatic.reason()).contains("catalog-reindex-requested");
        await().atMost(Duration.ofSeconds(30)).until(() -> runs.findReindexRequest().isEmpty());
        assertThat(runs.listRuns(Optional.empty(), 10)).hasSize(1);
    }

    // ------------------------------------------------------------------ index cleanup

    @Test
    void superseededIndicesAreDeletedOnlyAfterTheRetentionAndTheRollbackVersionOfSuggestionsIsKept()
            throws Exception {
        Instant now = Instant.now();
        String oldProducts = indexAdmin.createVersionedIndex(IndexTemplates.PRODUCTS,
                indexAdmin.nextVersion(IndexTemplates.PRODUCTS));
        String recentProducts = indexAdmin.createVersionedIndex(IndexTemplates.PRODUCTS,
                indexAdmin.nextVersion(IndexTemplates.PRODUCTS));
        String unknownProducts = indexAdmin.createVersionedIndex(IndexTemplates.PRODUCTS,
                indexAdmin.nextVersion(IndexTemplates.PRODUCTS));
        runs.markIndexRetired(oldProducts, "run-x", now.minus(Duration.ofHours(25)));
        runs.markIndexRetired(recentProducts, "run-y", now.minus(Duration.ofHours(1)));

        String suggestOld = indexAdmin.createVersionedIndex(IndexTemplates.SEARCH_SUGGESTIONS,
                indexAdmin.nextVersion(IndexTemplates.SEARCH_SUGGESTIONS));
        String suggestMid = indexAdmin.createVersionedIndex(IndexTemplates.SEARCH_SUGGESTIONS,
                indexAdmin.nextVersion(IndexTemplates.SEARCH_SUGGESTIONS));
        String suggestRollback = indexAdmin.createVersionedIndex(IndexTemplates.SEARCH_SUGGESTIONS,
                indexAdmin.nextVersion(IndexTemplates.SEARCH_SUGGESTIONS));
        String suggestInFlight = indexAdmin.createVersionedIndex(IndexTemplates.SEARCH_SUGGESTIONS,
                indexAdmin.nextVersion(IndexTemplates.SEARCH_SUGGESTIONS));
        runs.markIndexRetired(suggestOld, null, now.minus(Duration.ofHours(48)));
        runs.markIndexRetired(suggestMid, null, now.minus(Duration.ofHours(30)));
        runs.markIndexRetired(suggestRollback, null, now.minus(Duration.ofHours(26)));

        int deleted = cleanup.run();

        assertThat(deleted).isEqualTo(3);
        assertThat(indexAdmin.indexExists(oldProducts)).isFalse();
        assertThat(indexAdmin.indexExists(recentProducts)).isTrue();
        // First seen now: it gets a marker and is neither deleted nor taken for a rollback version.
        assertThat(indexAdmin.indexExists(unknownProducts)).isTrue();
        assertThat(runs.retiredIndices()).containsKey(unknownProducts);
        // The newest retired suggestion version is the rollback version and survives its age.
        assertThat(indexAdmin.indexExists(suggestRollback)).isTrue();
        assertThat(indexAdmin.indexExists(suggestOld)).isFalse();
        assertThat(indexAdmin.indexExists(suggestMid)).isFalse();
        assertThat(indexAdmin.indexExists(suggestInFlight)).isTrue();
        // What serves traffic is never touched.
        assertThat(indexAdmin.resolveAlias(properties.aliases().products())).isPresent();
        assertThat(indexAdmin.indexExists(indexAdmin.resolveAlias(properties.aliases().products()).orElseThrow()))
                .isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private String saveAbandonedRun(String stage, int resumeAttempts) throws Exception {
        String runId = UUID.randomUUID().toString();
        String categories = indexAdmin.createVersionedIndex(IndexTemplates.CATEGORIES,
                indexAdmin.nextVersion(IndexTemplates.CATEGORIES));
        String products = indexAdmin.createVersionedIndex(IndexTemplates.PRODUCTS,
                indexAdmin.nextVersion(IndexTemplates.PRODUCTS));
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("stage", stage);
        checkpoint.put("products_index", products);
        checkpoint.put("categories_index", categories);
        checkpoint.put("resume_attempts", resumeAttempts);
        Map<String, Object> offsets = new LinkedHashMap<>();
        offsets.put("category", List.of(Map.of("topic", properties.topics().category(), "partition", 0, "offset", 0)));
        Instant started = Instant.now().minus(Duration.ofMinutes(10));
        // No lease exists for this run: its owner is gone.
        runs.saveRun(new ReindexRunDocument(ReindexRunDocument.DOC_KIND, runId, "BUILDING", "BUILDING",
                "crashed run", "admin-1", started, null, started, null, null, products,
                Map.of("categories_exported", 1L), List.of(offsets), checkpoint));
        return runId;
    }

    private ReindexRunDocument awaitStatus(String runId, String status) {
        return await().atMost(RUN_TIMEOUT).pollInterval(Duration.ofMillis(500))
                .until(() -> runs.findRun(runId), found -> found.map(run -> status.equals(run.status())
                        || (run.isTerminal() && !status.equals(run.status()) && fail(run, status))).orElse(false))
                .orElseThrow();
    }

    /** A run that ends differently from what the test waits for fails fast with its reason. */
    private static boolean fail(ReindexRunDocument run, String expected) {
        throw new AssertionError("run " + run.runId() + " ended " + run.status() + " instead of " + expected
                + ": " + run.failureCode() + " " + run.failureMessage());
    }

    private void awaitNoLiveLease() {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
            Optional<LeaseLockDocument> lease = runs.readLease(LeaseLockDocument.REINDEX_LOCK);
            return lease.isEmpty() || lease.get().expired(Instant.now());
        });
    }

    private void seedCurrentIndex(long catalogRevision, long sellingPrice) throws IOException {
        CatalogPayloads.ProductSnapshot product = new CatalogPayloads.ProductSnapshot(
                ExportStubServer.PRODUCT_ID, ExportStubServer.CATEGORY_ID, "iPhone 15", "iphone-15", "A phone",
                Map.of("brand", "Apple"), "ACTIVE", catalogRevision, Instant.now());
        CatalogPayloads.SkuSnapshot sku = new CatalogPayloads.SkuSnapshot(
                ExportStubServer.SKU_ID, "IP15-BLK", 34_990_000L, sellingPrice, "ACTIVE",
                Map.of("color", "Black"), null, Instant.now());
        EventBatch batch = new EventBatch(properties.aliases().productsWrite());
        batch.add(writer.catalogUpsert(batch.targetIndex(), ExportStubServer.SKU_ID,
                CatalogProjection.toFields(product, sku, Optional.empty()),
                catalogRevision, Timestamps.nowIso(), "seed"));
        batch.add(writer.availabilityPatch(batch.targetIndex(), ExportStubServer.SKU_ID, true, 1L,
                Timestamps.nowIso(), Timestamps.nowIso(), "seed-availability"));
        executor.execute(properties.topics().product(), batch);
        refreshAll();
    }

    private Optional<ProductDocument> read(String index, String skuId) throws IOException {
        client.indices().refresh(r -> r.index(index));
        var response = client.get(builder -> builder.index(index).id(skuId), ProductDocument.class);
        return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
    }

    private static HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "admin-1");
        headers.set("X-User-Roles", "ADMIN");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String path, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, adminHeaders()), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
    }

    private static String controlEvent(String rootCategoryId, long revision) {
        return """
                {"specversion":"1.0","id":"evt-%s","type":"com.ecommerce.catalog.reindex-requested.v1",
                 "source":"/services/product","subject":"categories/%s","time":"%s",
                 "datacontenttype":"application/json","dataschema":"/contracts/events/product/v1/schemas/catalog-reindex-requested.schema.json",
                 "data":{"reason":"CATEGORY_TREE_CHANGED","root_category_id":"%s","category_revision":%d,
                         "requested_at":"%s"}}
                """.formatted(UUID.randomUUID(), rootCategoryId, Instant.now(), rootCategoryId, revision, Instant.now());
    }
}
