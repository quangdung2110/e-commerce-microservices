package com.platform.productservice.integration;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.infrastructure.event.CatalogEventTypes;
import com.platform.productservice.web.dto.CategoryDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-PRODUCT-003: category_revision là ordering guard, đổi cây category phát
 * control event thay vì fan-out subtree (ADR-010).
 */
class CategoryCommandIntegrationTest extends AbstractCatalogIntegrationTest {

    @Test
    @DisplayName("Tạo category con làm parent hết là lá nên parent được phát snapshot mới")
    void creatingFirstChildRepublishesParent() {
        UUID rootId = givenLeafCategory("electronics");
        givenChildCategory(rootId, "phones");

        List<OutboxRow> events = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED);
        assertThat(events).hasSize(3);

        OutboxRow parentUpdate = events.get(2);
        assertThat(parentUpdate.partitionKey()).isEqualTo(rootId);
        assertThat(parentUpdate.sequence()).isEqualTo(2L);
        assertThat(parentUpdate.data().path("category").path("is_leaf").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Category snapshot mang path dẫn xuất từ root")
    void categorySnapshotCarriesDerivedPath() {
        UUID rootId = givenLeafCategory("electronics");
        UUID childId = givenChildCategory(rootId, "phones");

        OutboxRow childEvent = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED).stream()
                .filter(row -> row.partitionKey().equals(childId))
                .findFirst()
                .orElseThrow();

        assertThat(childEvent.data().path("category").path("path").size()).isEqualTo(2);
        assertThat(childEvent.data().path("category").path("path").get(0).path("slug").asText())
                .isEqualTo("electronics");
        assertThat(childEvent.changeKind()).isEqualTo("CATEGORY_CREATED");
    }

    @Test
    @DisplayName("Rename phát thêm catalog-reindex-requested với root category id")
    void renameRequestsFullReindex() {
        UUID rootId = givenLeafCategory("electronics");
        UUID childId = givenChildCategory(rootId, "phones");

        categoryCommandService.replace(childId,
                new CategoryDtos.ReplaceCategoryRequest(rootId, "Smartphones", "smartphones"));

        List<OutboxRow> reindex = outboxRowsOfType(CatalogEventTypes.CATALOG_REINDEX_REQUESTED);
        assertThat(reindex).hasSize(1);
        assertThat(reindex.get(0).partitionKey()).isEqualTo(rootId);
        assertThat(reindex.get(0).data().path("reason").asText()).isEqualTo("CATEGORY_TREE_CHANGED");
        assertThat(reindex.get(0).data().path("root_category_id").asText()).isEqualTo(rootId.toString());
    }

    @Test
    @DisplayName("Sửa category không đổi path thì không yêu cầu reindex")
    void unchangedPathDoesNotRequestReindex() {
        UUID rootId = givenLeafCategory("electronics");

        categoryCommandService.replace(rootId,
                new CategoryDtos.ReplaceCategoryRequest(null, "electronics", "electronics"));

        assertThat(outboxRowsOfType(CatalogEventTypes.CATALOG_REINDEX_REQUESTED)).isEmpty();
        assertThat(outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED)).hasSize(2);
    }

    @Test
    @DisplayName("Không đặt parent tạo cycle")
    void rejectsCycle() {
        UUID rootId = givenLeafCategory("electronics");
        UUID childId = givenChildCategory(rootId, "phones");

        assertThatThrownBy(() -> categoryCommandService.replace(rootId,
                new CategoryDtos.ReplaceCategoryRequest(childId, "electronics", "electronics")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_CYCLE_DETECTED);
    }

    @Test
    @DisplayName("Category đang chứa Product không được nhận thêm category con")
    void rejectsChildUnderCategoryHoldingProducts() {
        UUID leafId = givenLeafCategory("phones");
        givenProduct(leafId, "pixel", 1000L);

        assertThatThrownBy(() -> givenChildCategory(leafId, "flagship"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_LEAF);
    }

    @Test
    @DisplayName("Chỉ xóa được category không còn con và không còn Product")
    void deleteRequiresEmptyCategory() {
        UUID rootId = givenLeafCategory("electronics");
        UUID childId = givenChildCategory(rootId, "phones");
        givenProduct(childId, "pixel", 1000L);

        assertThatThrownBy(() -> categoryCommandService.delete(rootId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_EMPTY);

        assertThatThrownBy(() -> categoryCommandService.delete(childId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_EMPTY);
    }

    @Test
    @DisplayName("Xóa category lá phát tombstone và trả parent về trạng thái lá")
    void deleteEmitsTombstoneAndRepublishesParent() {
        UUID rootId = givenLeafCategory("electronics");
        UUID childId = givenChildCategory(rootId, "phones");

        categoryCommandService.delete(childId);

        List<OutboxRow> tombstones = outboxRowsOfType(CatalogEventTypes.CATEGORY_DELETED);
        assertThat(tombstones).hasSize(1);
        assertThat(tombstones.get(0).partitionKey()).isEqualTo(childId);
        assertThat(tombstones.get(0).sequence()).isEqualTo(2L);

        OutboxRow parentAfterDelete = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED)
                .get(outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED).size() - 1);
        assertThat(parentAfterDelete.partitionKey()).isEqualTo(rootId);
        assertThat(parentAfterDelete.data().path("category").path("is_leaf").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Mapping category–attribute tăng category_revision và phát CATEGORY_ATTRIBUTE_CHANGED")
    void attributeMappingBumpsCategoryRevision() {
        UUID categoryId = givenLeafCategory("phones");
        UUID attributeId = givenAttribute("brand");

        givenMapping(categoryId, attributeId, false, false);

        OutboxRow last = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED)
                .get(outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED).size() - 1);
        assertThat(last.changeKind()).isEqualTo("CATEGORY_ATTRIBUTE_CHANGED");
        assertThat(last.sequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Không đặt required cho attribute mà Product hiện hữu chưa có giá trị")
    void rejectsRequiredMappingBreakingExistingProducts() {
        UUID categoryId = givenLeafCategory("phones");
        UUID attributeId = givenAttribute("brand");
        givenProduct(categoryId, "pixel", 1000L);

        assertThatThrownBy(() -> givenMapping(categoryId, attributeId, true, false))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_IN_USE);
    }

    @Test
    @DisplayName("PUT mapping là idempotent ở cấp resource")
    void putMappingIsIdempotent() {
        UUID categoryId = givenLeafCategory("phones");
        UUID attributeId = givenAttribute("brand");

        assertThat(categoryCommandService.putAttribute(categoryId, attributeId,
                new CategoryDtos.PutCategoryAttributeRequest(false, false)).created()).isTrue();
        assertThat(categoryCommandService.putAttribute(categoryId, attributeId,
                new CategoryDtos.PutCategoryAttributeRequest(false, true)).created()).isFalse();
    }

    @Test
    @DisplayName("Attribute còn được category tham chiếu thì không xóa được")
    void attributeInUseCannotBeDeleted() {
        UUID categoryId = givenLeafCategory("phones");
        UUID attributeId = givenAttribute("brand");
        givenMapping(categoryId, attributeId, false, false);

        assertThatThrownBy(() -> attributeService.delete(attributeId))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_IN_USE);
    }

    @Test
    @DisplayName("Attribute và option không phát event riêng ở v1")
    void attributeChangesEmitNoEvents() {
        givenAttribute("brand");

        assertThat(outboxRows()).isEmpty();
    }
}
