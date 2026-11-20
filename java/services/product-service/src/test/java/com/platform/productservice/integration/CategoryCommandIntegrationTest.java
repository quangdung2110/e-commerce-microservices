package com.platform.productservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.enums.AttributeValueType;
import com.platform.productservice.infrastructure.event.CatalogEventTypes;
import com.platform.productservice.web.dto.AttributeDtos;
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
        UUID attributeId = attributeService.create(new AttributeDtos.CreateAttributeRequest(
                "color", "Màu sắc", AttributeValueType.SELECT)).attributeId();
        attributeService.createOption(attributeId, new AttributeDtos.CreateAttributeOptionRequest(
                "black", "Đen", 0));

        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("Category snapshot mang facet_definitions theo mapping, sắp theo code")
    void categorySnapshotCarriesFacetDefinitions() {
        UUID categoryId = givenLeafCategory("phones");
        UUID color = givenAttribute("color", "Màu sắc", AttributeValueType.SELECT);
        UUID brand = givenAttribute("brand", "Thương hiệu", AttributeValueType.TEXT);

        assertThat(latestCategorySnapshot(categoryId).path("facet_definitions").isArray()).isTrue();
        assertThat(latestCategorySnapshot(categoryId).path("facet_definitions")).isEmpty();

        givenMapping(categoryId, color, false, true);
        givenMapping(categoryId, brand, true, false);

        JsonNode facets = latestCategorySnapshot(categoryId).path("facet_definitions");
        assertThat(facets).hasSize(2);
        assertThat(facets.get(0).path("code").asText()).isEqualTo("brand");
        assertThat(facets.get(0).path("label").asText()).isEqualTo("Thương hiệu");
        assertThat(facets.get(0).path("type").asText()).isEqualTo("TEXT");
        assertThat(facets.get(1).path("code").asText()).isEqualTo("color");
        assertThat(facets.get(1).path("type").asText()).isEqualTo("SELECT");
    }

    @Test
    @DisplayName("Bỏ mapping làm descriptor biến mất khỏi snapshot kế tiếp")
    void removingMappingDropsFacetDefinition() {
        UUID categoryId = givenLeafCategory("phones");
        UUID color = givenAttribute("color", "Màu sắc", AttributeValueType.SELECT);
        givenMapping(categoryId, color, false, true);

        categoryCommandService.deleteAttribute(categoryId, color);

        assertThat(latestCategorySnapshot(categoryId).path("facet_definitions")).isEmpty();
    }

    @Test
    @DisplayName("Đổi tên attribute đã map tăng category_revision và phát lại mọi category map nó")
    void renamingMappedAttributeRepublishesEveryCategory() {
        UUID phones = givenLeafCategory("phones");
        UUID laptops = givenLeafCategory("laptops");
        UUID unrelated = givenLeafCategory("books");
        UUID color = givenAttribute("color", "Màu sắc", AttributeValueType.SELECT);
        givenMapping(phones, color, false, true);
        givenMapping(laptops, color, false, true);
        int before = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED).size();

        attributeService.replace(color, new AttributeDtos.ReplaceAttributeRequest(
                "color", "Màu", AttributeValueType.SELECT));

        List<OutboxRow> events = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED);
        assertThat(events).hasSize(before + 2);
        for (UUID categoryId : List.of(phones, laptops)) {
            OutboxRow latest = latestCategoryRow(categoryId);
            assertThat(latest.sequence()).isEqualTo(3L);
            assertThat(latest.changeKind()).isEqualTo("CATEGORY_ATTRIBUTE_CHANGED");
            assertThat(latest.data().path("category").path("facet_definitions").get(0).path("label").asText())
                    .isEqualTo("Màu");
        }
        assertThat(latestCategoryRow(unrelated).sequence()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Đổi tên attribute chưa map hoặc đổi nhãn option không phát event")
    void renamingUnmappedAttributeEmitsNothing() {
        UUID color = givenAttribute("color", "Màu sắc", AttributeValueType.SELECT);
        UUID optionId = attributeService.createOption(color,
                new AttributeDtos.CreateAttributeOptionRequest("black", "Đen", 0)).optionId();

        attributeService.replace(color, new AttributeDtos.ReplaceAttributeRequest(
                "color", "Màu", AttributeValueType.SELECT));
        attributeService.replaceOption(color, optionId,
                new AttributeDtos.ReplaceAttributeOptionRequest("black", "Đen tuyền", 1));

        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("Đổi nhãn option của attribute đã map không phát lại category (option không nằm trong descriptor)")
    void optionLabelChangeDoesNotRepublishCategory() {
        UUID categoryId = givenLeafCategory("phones");
        UUID color = givenAttribute("color", "Màu sắc", AttributeValueType.SELECT);
        UUID optionId = attributeService.createOption(color,
                new AttributeDtos.CreateAttributeOptionRequest("black", "Đen", 0)).optionId();
        givenMapping(categoryId, color, false, true);
        int before = outboxRows().size();

        attributeService.replaceOption(color, optionId,
                new AttributeDtos.ReplaceAttributeOptionRequest("black", "Đen tuyền", 0));

        assertThat(outboxRows()).hasSize(before);
    }

    private JsonNode latestCategorySnapshot(UUID categoryId) {
        return latestCategoryRow(categoryId).data().path("category");
    }

    private OutboxRow latestCategoryRow(UUID categoryId) {
        List<OutboxRow> rows = outboxRowsOfType(CatalogEventTypes.CATEGORY_UPSERTED).stream()
                .filter(row -> row.partitionKey().equals(categoryId))
                .toList();
        return rows.get(rows.size() - 1);
    }
}
