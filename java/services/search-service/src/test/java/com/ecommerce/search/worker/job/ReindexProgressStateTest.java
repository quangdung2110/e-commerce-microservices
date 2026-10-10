package com.ecommerce.search.worker.job;

import com.ecommerce.search.domain.document.ReindexRunDocument;
import com.ecommerce.search.worker.client.ExportPages;
import com.ecommerce.search.worker.job.ReindexProgressState.Stage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A resume rebuilds the run from the document it saved, after that document has been through
 * Elasticsearch as JSON (numbers come back as integers, instants as strings). Everything the
 * stages need to carry on has to survive that round trip.
 */
class ReindexProgressStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void everythingAStageNeedsSurvivesTheJsonRoundTrip() throws Exception {
        Instant created = Instant.parse("2026-10-10T01:00:00Z");
        Instant expires = created.plus(Duration.ofHours(24));
        ReindexProgressState state = new ReindexProgressState();
        state.advanceTo(Stage.BUILD_RATING);
        state.recordIndices("products_v4", "categories_v4");
        state.recordCategoriesCursor("cat-cursor");
        state.recordProductsCursor("prod-cursor");
        state.addCategories(12);
        state.addProducts(340);
        state.addAvailabilitySnapshots(900);
        state.addRatingAggregates(55);
        state.setResumeAttempts(2);
        state.recordOffsets("category", List.of(new ExportPages.ReplayOffset("t.category.events", 0, 7)));
        state.recordOffsets("product", List.of(
                new ExportPages.ReplayOffset("t.product.events", 0, 9),
                new ExportPages.ReplayOffset("t.product.events", 1, 11)));
        state.recordAvailabilitySession(new ExportPages.SnapshotSession("snap-9", 500L, created, expires));
        state.recordAvailabilityCursor("avail-cursor");
        state.recordAvailabilitySequence(520L);
        state.markAvailabilityHandoffProven();
        state.deferAvailability("sku-late-1");
        state.deferAvailability("sku-late-2");
        ExportPages.RatingExportSession rating = new ExportPages.RatingExportSession("rev-3", expires,
                List.of(new ExportPages.ReplayOffset("t.review.events", 0, 4)));
        state.recordRatingSession(rating);
        state.replaceOffsets("review", rating.replayFromOffsets());
        state.recordRatingAfter("prod-500");
        state.recordCatchUpEnd(Map.of("t.product.events:0", 120L, "t.product.events:1", 99L),
                Instant.parse("2026-10-10T02:00:00Z"));
        state.recordPrevious("products_v3", "categories_v3", Instant.parse("2026-10-10T03:00:00Z"));
        state.recordTailReplay("COMPLETE");

        ReindexProgressState restored = ReindexProgressState.restore(throughJson(state));

        assertThat(restored.stage()).isEqualTo(Stage.BUILD_RATING);
        assertThat(restored.productsIndex()).isEqualTo("products_v4");
        assertThat(restored.categoriesIndex()).isEqualTo("categories_v4");
        assertThat(restored.categoriesCursor()).isEqualTo("cat-cursor");
        assertThat(restored.productsCursor()).isEqualTo("prod-cursor");
        assertThat(restored.resumeAttempts()).isEqualTo(2);
        assertThat(restored.toMap()).isEqualTo(state.toMap());
        assertThat(restored.allOffsets()).containsExactlyInAnyOrderElementsOf(state.allOffsets());
        assertThat(restored.availabilitySession().snapshotId()).isEqualTo("snap-9");
        assertThat(restored.availabilitySession().exportWatermark()).isEqualTo(500L);
        assertThat(restored.availabilitySession().expiresAt()).isEqualTo(expires);
        assertThat(restored.availabilityCursor()).isEqualTo("avail-cursor");
        assertThat(restored.availabilityResumeSequence()).isEqualTo(520L);
        assertThat(restored.isAvailabilityHandoffProven()).isTrue();
        assertThat(restored.deferredAvailabilitySkus()).containsExactlyInAnyOrder("sku-late-1", "sku-late-2");
        assertThat(restored.ratingSession().sessionId()).isEqualTo("rev-3");
        assertThat(restored.ratingSession().replayFromOffsets()).isEqualTo(rating.replayFromOffsets());
        assertThat(restored.ratingAfter()).isEqualTo("prod-500");
        assertThat(restored.catchUpEndPositions()).containsEntry("t.product.events:0", 120L)
                .containsEntry("t.product.events:1", 99L);
        assertThat(restored.catchUpEndedAt()).isEqualTo(Instant.parse("2026-10-10T02:00:00Z"));
        assertThat(restored.previousProductsIndex()).isEqualTo("products_v3");
        assertThat(restored.previousCategoriesIndex()).isEqualTo("categories_v3");
        assertThat(restored.checkpointAsMap()).containsEntry("tail_replay", "COMPLETE");
    }

    @Test
    void aDocumentWithoutAStageRestartsFromTheBeginning() {
        ReindexRunDocument legacy = new ReindexRunDocument(ReindexRunDocument.DOC_KIND, "run", "BUILDING",
                "BUILDING", "r", "a", Instant.now(), null, Instant.now(), null, null, null, Map.of(),
                List.of(), Map.of());

        ReindexProgressState restored = ReindexProgressState.restore(legacy);

        assertThat(restored.stage()).isEqualTo(Stage.CREATE_INDICES);
        assertThat(restored.productsIndex()).isNull();
        assertThat(restored.resumeAttempts()).isZero();
    }

    @Test
    void anUnknownStageNameRestartsFromTheBeginning() {
        ReindexRunDocument odd = new ReindexRunDocument(ReindexRunDocument.DOC_KIND, "run", "BUILDING",
                "BUILDING", "r", "a", Instant.now(), null, Instant.now(), null, null, null, Map.of(),
                List.of(), Map.of("stage", "SOMETHING_NEW"));

        assertThat(ReindexProgressState.restore(odd).stage()).isEqualTo(Stage.CREATE_INDICES);
    }

    @Test
    void aNewAvailabilitySessionDropsEverythingTheOldOneEstablished() {
        ReindexProgressState state = new ReindexProgressState();
        state.recordAvailabilitySession(new ExportPages.SnapshotSession("a", 1L, null, null));
        state.recordAvailabilityCursor("c");
        state.recordAvailabilitySequence(9L);
        state.markAvailabilityHandoffProven();
        state.addAvailabilitySnapshots(10);

        state.recordAvailabilitySession(new ExportPages.SnapshotSession("b", 50L, null, null));

        assertThat(state.availabilityCursor()).isNull();
        assertThat(state.availabilityResumeSequence()).isEqualTo(50L);
        assertThat(state.isAvailabilityHandoffProven()).isFalse();
        assertThat(state.toMap().get("stock_snapshots_imported")).isEqualTo(0L);
    }

    /** Saves the state the way the job does and reads it back the way Elasticsearch returns it. */
    private static ReindexRunDocument throughJson(ReindexProgressState state) throws Exception {
        ReindexRunDocument saved = new ReindexRunDocument(ReindexRunDocument.DOC_KIND, "run", "BUILDING",
                "BUILDING", "r", "a", Instant.now(), null, Instant.now(), null, null, state.productsIndex(),
                state.toMap(), List.of(state.offsetsAsMap()), state.checkpointAsMap());
        String json = MAPPER.writeValueAsString(saved);
        return MAPPER.readValue(json, new TypeReference<ReindexRunDocument>() { });
    }
}
