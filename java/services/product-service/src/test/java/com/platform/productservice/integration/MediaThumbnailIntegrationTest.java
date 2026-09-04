package com.platform.productservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.infrastructure.event.CatalogEventTypes;
import com.platform.productservice.service.CatalogExportService;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thumbnail hiệu lực end-to-end (ADR-013): giá trị nằm trong outbox payload thật,
 * fan-out theo đúng tập SKU bị ảnh hưởng, và export HTTP trả cùng giá trị với
 * event nên full reindex không lệch so với luồng streaming.
 */
class MediaThumbnailIntegrationTest extends AbstractCatalogIntegrationTest {

    @Autowired
    private CatalogExportService exportService;

    @Test
    @DisplayName("Ảnh SKU thắng ảnh Product trong snapshot; SKU không có ảnh riêng thì fallback")
    void snapshotPrefersSkuImageThenFallsBack() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Pixel", "pixel", null, Map.of(),
                        List.of(image("catalog/pixel/shared.jpg", 0)),
                        List.of(skuWithMedia("P-BLACK", List.of(image("catalog/pixel/black.jpg", 5))),
                                sku("P-WHITE"))));

        assertThat(thumbnailOf("P-BLACK")).isEqualTo("catalog/pixel/black.jpg");
        assertThat(thumbnailOf("P-WHITE")).isEqualTo("catalog/pixel/shared.jpg");
    }

    @Test
    @DisplayName("VIDEO không được chọn làm thumbnail ở bất kỳ cấp nào")
    void videoIsNeverChosen() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Pixel", "pixel", null, Map.of(),
                        List.of(new ProductDtos.CreateMediaInput(
                                        MediaType.VIDEO, "catalog/pixel/product.mp4", null, 0),
                                image("catalog/pixel/shared.jpg", 9)),
                        List.of(skuWithMedia("P-BLACK", List.of(new ProductDtos.CreateMediaInput(
                                MediaType.VIDEO, "catalog/pixel/black.mp4", null, 0))))));

        assertThat(thumbnailOf("P-BLACK")).isEqualTo("catalog/pixel/shared.jpg");
    }

    @Test
    @DisplayName("Product không có ảnh nào phát thumbnail null, không phải field vắng mặt")
    void snapshotCarriesExplicitNull() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1_000L);

        JsonNode thumbnail = snapshotOf(product.skus().get(0).skuCode())
                .data().path("sku").path("thumbnail_storage_key");
        assertThat(thumbnail.isMissingNode()).isFalse();
        assertThat(thumbnail.isNull()).isTrue();
    }

    @Test
    @DisplayName("Thêm ảnh cấp Product chỉ đổi thumbnail của SKU đang fallback")
    void productMediaFanOutHitsFallbackSkusOnly() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Pixel", "pixel", null, Map.of(), List.of(),
                        List.of(skuWithMedia("P-BLACK", List.of(image("catalog/pixel/black.jpg", 0))),
                                sku("P-WHITE"))));
        assertThat(thumbnailOf("P-BLACK")).isEqualTo("catalog/pixel/black.jpg");
        assertThat(snapshotOf("P-WHITE").data().path("sku").path("thumbnail_storage_key").isNull()).isTrue();

        mediaCommandService.create(product.productId(), ProductEntity.etagOf(1L),
                new ProductDtos.CreateMediaRequest(null, MediaType.IMAGE,
                        "catalog/pixel/shared.jpg", null, 0));

        // Cả hai SKU nhận snapshot mới vì media[] cấp Product đi kèm mọi SKU,
        // nhưng chỉ SKU đang fallback đổi thumbnail.
        List<OutboxRow> fanOut = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.sequence() == 2L)
                .toList();
        assertThat(fanOut).hasSize(2);
        assertThat(thumbnailAt(fanOut, "P-BLACK")).isEqualTo("catalog/pixel/black.jpg");
        assertThat(thumbnailAt(fanOut, "P-WHITE")).isEqualTo("catalog/pixel/shared.jpg");
    }

    @Test
    @DisplayName("Xóa ảnh SKU đưa thumbnail về ảnh Product ở snapshot kế tiếp")
    void removingSkuImageRevertsToProductImage() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Pixel", "pixel", null, Map.of(),
                        List.of(image("catalog/pixel/shared.jpg", 0)),
                        List.of(skuWithMedia("P-BLACK", List.of(image("catalog/pixel/black.jpg", 0))))));
        UUID skuMediaId = mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(product.productId())
                .stream()
                .filter(media -> media.getStorageKey().equals("catalog/pixel/black.jpg"))
                .findFirst().orElseThrow().getMediaId();

        mediaCommandService.delete(product.productId(), skuMediaId, ProductEntity.etagOf(1L));

        List<OutboxRow> latest = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.sequence() == 2L)
                .toList();
        assertThat(thumbnailAt(latest, "P-BLACK")).isEqualTo("catalog/pixel/shared.jpg");
    }

    @Test
    @DisplayName("Export trả cùng thumbnail với event nên full reindex không lệch")
    void exportMatchesEvent() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Pixel", "pixel", null, Map.of(),
                        List.of(image("catalog/pixel/shared.jpg", 0)),
                        List.of(skuWithMedia("P-BLACK", List.of(image("catalog/pixel/black.jpg", 0))),
                                sku("P-WHITE"))));

        ProductDtos.ProductExportPageResponse page = exportService.exportProducts(null, 50);
        List<ProductDtos.SkuResponse> exported = page.products().stream()
                .filter(item -> item.productId().equals(product.productId()))
                .findFirst().orElseThrow().skus();

        assertThat(exported).allSatisfy(sku ->
                assertThat(sku.thumbnailStorageKey()).isEqualTo(thumbnailOf(sku.skuCode())));
        assertThat(exportedThumbnail(exported, "P-BLACK")).isEqualTo("catalog/pixel/black.jpg");
        assertThat(exportedThumbnail(exported, "P-WHITE")).isEqualTo("catalog/pixel/shared.jpg");
    }

    // ---------------------------------------------------------------- helpers

    /** Thumbnail của một SKU trong sku-upserted mới nhất của nó. */
    private String thumbnailOf(String skuCode) {
        return textOrNull(snapshotOf(skuCode).data().path("sku").path("thumbnail_storage_key"));
    }

    /** NullNode.asText() trả chuỗi "null", nên phải phân biệt tường minh. */
    private String textOrNull(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private OutboxRow snapshotOf(String skuCode) {
        List<OutboxRow> rows = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.data().path("sku").path("sku_code").asText().equals(skuCode))
                .toList();
        assertThat(rows).as("không có sku-upserted nào cho %s", skuCode).isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    private String thumbnailAt(List<OutboxRow> rows, String skuCode) {
        return textOrNull(rows.stream()
                .filter(row -> row.data().path("sku").path("sku_code").asText().equals(skuCode))
                .findFirst().orElseThrow()
                .data().path("sku").path("thumbnail_storage_key"));
    }

    private String exportedThumbnail(List<ProductDtos.SkuResponse> skus, String skuCode) {
        return skus.stream()
                .filter(sku -> sku.skuCode().equals(skuCode))
                .findFirst().orElseThrow()
                .thumbnailStorageKey();
    }

    private ProductDtos.CreateMediaInput image(String storageKey, int sortOrder) {
        return new ProductDtos.CreateMediaInput(MediaType.IMAGE, storageKey, null, sortOrder);
    }

    private ProductDtos.CreateSkuRequest sku(String code) {
        return new ProductDtos.CreateSkuRequest(code, 1_000L, SkuStatus.ACTIVE, Map.of(), List.of());
    }

    private ProductDtos.CreateSkuRequest skuWithMedia(String code, List<ProductDtos.CreateMediaInput> media) {
        return new ProductDtos.CreateSkuRequest(code, 1_000L, SkuStatus.ACTIVE, Map.of(), media);
    }
}
