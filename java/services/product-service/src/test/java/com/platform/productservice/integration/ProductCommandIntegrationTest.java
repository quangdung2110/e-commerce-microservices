package com.platform.productservice.integration;

import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.infrastructure.event.CatalogEventTypes;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WF-PRODUCT-001 và WF-PRODUCT-002 trên database thật: revision, outbox,
 * ETag concurrency và hard delete/tombstone.
 */
class ProductCommandIntegrationTest extends AbstractCatalogIntegrationTest {

    @Test
    @DisplayName("Tạo Product ghi revision 1 và một sku-upserted cho mỗi SKU trong cùng transaction")
    void createProductWritesSnapshotPerSku() {
        UUID categoryId = givenLeafCategory("phones");

        ProductDtos.ProductResponse product = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Pixel 9", "pixel-9", null, Map.of(), List.of(),
                List.of(sku("P9-BLACK", 25_000_000L), sku("P9-WHITE", 26_000_000L))));

        assertThat(product.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(product.catalogRevision()).isEqualTo(1L);
        assertThat(product.skus()).hasSize(2);
        // Product mới chưa có markdown nên giá công bố bằng base price.
        assertThat(product.skus()).allSatisfy(sku ->
                assertThat(sku.sellingPriceVnd()).isEqualTo(sku.basePriceVnd()));

        List<OutboxRow> events = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED);
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(row -> {
            assertThat(row.aggregateType()).isEqualTo("Product");
            assertThat(row.aggregateId()).isEqualTo(product.productId());
            // ADR-012: mọi event Product/SKU dùng product_id làm partition key.
            assertThat(row.partitionKey()).isEqualTo(product.productId());
            assertThat(row.sequence()).isEqualTo(1L);
            assertThat(row.changeKind()).isEqualTo("PRODUCT_CREATED");
        });
    }

    @Test
    @DisplayName("sku_code trùng bị từ chối, kể cả trùng trong cùng một request")
    void rejectsDuplicateSkuCode() {
        UUID categoryId = givenLeafCategory("phones");

        assertThatThrownBy(() -> productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Dup", "dup", null, Map.of(), List.of(),
                List.of(sku("SAME", 1000L), sku("SAME", 2000L)))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.SKU_CODE_ALREADY_EXISTS);

        givenProduct(categoryId, "first", 1000L);
        assertThatThrownBy(() -> productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Second", "second", null, Map.of(), List.of(),
                List.of(sku("FIRST-1", 1000L)))))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.SKU_CODE_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("Không gán được Product vào category không phải lá")
    void rejectsNonLeafCategory() {
        UUID rootId = givenLeafCategory("electronics");
        givenChildCategory(rootId, "phones");

        assertThatThrownBy(() -> givenProduct(rootId, "bad", 1000L))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_LEAF);
    }

    @Test
    @DisplayName("Sửa Product-level tăng revision đúng một lần và phát snapshot cho mọi SKU")
    void replaceBumpsRevisionOnce() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Pixel 9", "pixel-9", null, Map.of(), List.of(),
                List.of(sku("P9-BLACK", 25_000_000L), sku("P9-WHITE", 26_000_000L))));

        productCommandService.replace(product.productId(), ProductEntity.etagOf(1L),
                new ProductDtos.ReplaceProductRequest(categoryId, "Pixel 9 Pro", "pixel-9-pro",
                        "New copy", Map.of()));

        assertThat(currentRevision(product.productId())).isEqualTo(2L);
        List<OutboxRow> updates = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("PRODUCT_UPDATED"))
                .toList();
        assertThat(updates).hasSize(2);
        assertThat(updates).allSatisfy(row -> assertThat(row.sequence()).isEqualTo(2L));
    }

    @Test
    @DisplayName("If-Match lệch revision trả PRODUCT_REVISION_MISMATCH và không ghi gì")
    void rejectsStaleIfMatch() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);

        assertThatThrownBy(() -> productCommandService.replace(product.productId(),
                ProductEntity.etagOf(99L),
                new ProductDtos.ReplaceProductRequest(categoryId, "x", "x", null, Map.of())))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_REVISION_MISMATCH);

        assertThat(currentRevision(product.productId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("If-Match sai định dạng bị từ chối trước khi chạm dữ liệu")
    void rejectsMalformedIfMatch() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);

        assertThatThrownBy(() -> productCommandService.delete(product.productId(), "*"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("Activate cần tối thiểu một SKU ACTIVE")
    void activateRequiresActiveSku() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Draft", "draft", null, Map.of(), List.of(),
                        List.of(new ProductDtos.CreateSkuRequest("D-1", 1000L, SkuStatus.INACTIVE,
                                Map.of(), List.of()))));

        assertThatThrownBy(() -> productCommandService.changeStatus(product.productId(),
                ProductEntity.etagOf(1L), ProductStatus.ACTIVE))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_SUBMITTABLE);
    }

    @Test
    @DisplayName("Hide/show không đọc Inventory và phát status snapshot")
    void hideAndShowAreAdminOnly() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);

        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(1L), ProductStatus.ACTIVE);
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(2L), ProductStatus.HIDDEN);
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(3L), ProductStatus.ACTIVE);

        assertThat(currentRevision(product.productId())).isEqualTo(4L);
        List<OutboxRow> statusEvents = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("PRODUCT_STATUS_CHANGED"))
                .toList();
        assertThat(statusEvents).hasSize(3);
        assertThat(statusEvents.get(2).data().path("product").path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Không xóa được SKU cuối cùng của Product")
    void refusesToDeleteLastSku() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);
        UUID skuId = product.skus().get(0).skuId();

        assertThatThrownBy(() -> skuCommandService.delete(product.productId(), skuId, etagOf(product.productId())))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.SKU_LAST_DELETE_FORBIDDEN);

        assertThat(skuRepository.countByProductId(product.productId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("Xóa SKU phát tombstone mang revision kế tiếp")
    void deleteSkuEmitsTombstone() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Pixel", "pixel", null, Map.of(), List.of(),
                List.of(sku("P-1", 1000L), sku("P-2", 2000L))));
        UUID skuId = product.skus().get(0).skuId();

        skuCommandService.delete(product.productId(), skuId, ProductEntity.etagOf(1L));

        List<OutboxRow> tombstones = outboxRowsOfType(CatalogEventTypes.SKU_DELETED);
        assertThat(tombstones).hasSize(1);
        assertThat(tombstones.get(0).sequence()).isEqualTo(2L);
        assertThat(tombstones.get(0).data().path("sku_id").asText()).isEqualTo(skuId.toString());
        assertThat(currentRevision(product.productId())).isEqualTo(2L);
        assertThat(skuRepository.findById(skuId)).isEmpty();
    }

    @Test
    @DisplayName("Hard delete Product phát tombstone kèm sku_ids rồi cascade dọn dữ liệu con")
    void deleteProductEmitsTombstoneAndCascades() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Pixel", "pixel", null, Map.of(),
                List.of(new ProductDtos.CreateMediaInput(
                        com.platform.productservice.domain.enums.MediaType.IMAGE,
                        "catalog/pixel/main.jpg", null, 0)),
                List.of(sku("P-1", 1000L), sku("P-2", 2000L))));

        productCommandService.delete(product.productId(), ProductEntity.etagOf(1L));

        List<OutboxRow> tombstones = outboxRowsOfType(CatalogEventTypes.PRODUCT_DELETED);
        assertThat(tombstones).hasSize(1);
        assertThat(tombstones.get(0).sequence()).isEqualTo(2L);
        assertThat(tombstones.get(0).data().path("sku_ids").size()).isEqualTo(2);
        assertThat(tombstones.get(0).partitionKey()).isEqualTo(product.productId());

        assertThat(productRepository.findById(product.productId())).isEmpty();
        assertThat(skuRepository.countByProductId(product.productId())).isZero();
        assertThat(mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(product.productId())).isEmpty();
        // Tombstone phải sống sót sau khi aggregate bị xóa: outbox không có FK tới Product.
        assertThat(outboxRows()).isNotEmpty();
    }

    @Test
    @DisplayName("Thêm SKU chỉ phát snapshot cho SKU vừa thêm")
    void addingSkuEmitsOnlyItsOwnSnapshot() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);

        skuCommandService.create(product.productId(), ProductEntity.etagOf(1L),
                sku("EXTRA-1", 3000L));

        List<OutboxRow> created = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("SKU_CREATED"))
                .toList();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).data().path("sku").path("sku_code").asText()).isEqualTo("EXTRA-1");
        assertThat(created.get(0).sequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Media cấp Product ảnh hưởng mọi SKU, media gắn SKU chỉ ảnh hưởng SKU đó")
    void mediaFanOutMatchesScope() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Pixel", "pixel", null, Map.of(), List.of(),
                List.of(sku("P-1", 1000L), sku("P-2", 2000L))));
        UUID firstSkuId = product.skus().get(0).skuId();

        mediaCommandService.create(product.productId(), ProductEntity.etagOf(1L),
                new ProductDtos.CreateMediaRequest(null,
                        com.platform.productservice.domain.enums.MediaType.IMAGE,
                        "catalog/pixel/shared.jpg", null, 0));
        assertThat(outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("PRODUCT_UPDATED"))
                .toList()).hasSize(2);

        mediaCommandService.create(product.productId(), ProductEntity.etagOf(2L),
                new ProductDtos.CreateMediaRequest(firstSkuId,
                        com.platform.productservice.domain.enums.MediaType.IMAGE,
                        "catalog/pixel/sku.jpg", null, 1));
        List<OutboxRow> skuScoped = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).stream()
                .filter(row -> row.changeKind().equals("SKU_UPDATED"))
                .toList();
        assertThat(skuScoped).hasSize(1);
        assertThat(skuScoped.get(0).data().path("sku").path("sku_id").asText())
                .isEqualTo(firstSkuId.toString());
    }

    @Test
    @DisplayName("Snapshot SKU mang cả media của SKU lẫn media cấp Product")
    void skuSnapshotMergesProductLevelMedia() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(new ProductDtos.CreateProductRequest(
                categoryId, "Pixel", "pixel", null, Map.of(),
                List.of(new ProductDtos.CreateMediaInput(
                        com.platform.productservice.domain.enums.MediaType.IMAGE,
                        "catalog/pixel/shared.jpg", null, 0)),
                List.of(new ProductDtos.CreateSkuRequest("P-1", 1000L, SkuStatus.ACTIVE, Map.of(),
                        List.of(new ProductDtos.CreateMediaInput(
                                com.platform.productservice.domain.enums.MediaType.IMAGE,
                                "catalog/pixel/black.jpg", null, 0))))));

        OutboxRow snapshot = outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED).get(0);
        assertThat(snapshot.data().path("sku").path("media").size()).isEqualTo(2);
        assertThat(snapshot.data().path("sku").path("media").get(0).path("storage_key").asText())
                .isEqualTo("catalog/pixel/black.jpg");
        assertThat(product.media()).hasSize(1);
    }

    @Test
    @DisplayName("CHECK constraint chặn selling price vượt base price")
    void databaseRejectsSellingPriceAboveBase() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);
        UUID skuId = product.skus().get(0).skuId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE product_skus SET selling_price_vnd = base_price_vnd + 1 WHERE sku_id = ?", skuId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Slug sai định dạng bị từ chối trước khi ghi (PRODUCT-BR-013)")
    void createRejectsMalformedSlug() {
        UUID categoryId = givenLeafCategory("phones");
        List<String> malformed = List.of("Pixel-9", "pixel_9", "điện-thoại", "pixel 9",
                "-pixel", "pixel-", "pixel--9", "a".repeat(181));

        for (String slug : malformed) {
            assertThatThrownBy(() -> productCommandService.create(new ProductDtos.CreateProductRequest(
                    categoryId, "Pixel", slug, null, Map.of(), List.of(), List.of(sku("P-1", 1000L)))))
                    .as(slug)
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
        }
        assertThat(productRepository.count()).isZero();
        assertThat(outboxRowsOfType(CatalogEventTypes.SKU_UPSERTED)).isEmpty();
    }

    @Test
    @DisplayName("Đổi sang slug sai định dạng bị từ chối, slug cũ giữ nguyên")
    void replaceRejectsMalformedSlug() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel-9", 1000L);

        assertThatThrownBy(() -> productCommandService.replace(product.productId(), etagOf(product.productId()),
                new ProductDtos.ReplaceProductRequest(categoryId, "Pixel 9", "Pixel-9", null, Map.of())))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(productRepository.findById(product.productId()).orElseThrow().getSlug()).isEqualTo("pixel-9");
    }

    @Test
    @DisplayName("CHECK constraint chặn slug sai định dạng ở mọi đường ghi")
    void databaseRejectsMalformedSlug() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE products SET slug = 'Pixel_9' WHERE product_id = ?", product.productId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private ProductDtos.CreateSkuRequest sku(String code, long basePriceVnd) {
        return new ProductDtos.CreateSkuRequest(code, basePriceVnd, SkuStatus.ACTIVE, Map.of(), List.of());
    }
}
