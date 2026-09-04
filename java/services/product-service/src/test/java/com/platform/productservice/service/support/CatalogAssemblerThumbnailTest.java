package com.platform.productservice.service.support;

import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule thumbnail hiệu lực (PRODUCT-BR-012, ADR-013): IMAGE của chính SKU có
 * {@code sort_order} nhỏ nhất, fallback IMAGE cấp Product có {@code sort_order}
 * nhỏ nhất, còn lại {@code null}.
 */
class CatalogAssemblerThumbnailTest {

    private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

    private final CatalogAssembler assembler = new CatalogAssembler();

    private final UUID productId = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();
    private final UUID otherSkuId = UUID.randomUUID();

    @Test
    @DisplayName("Ảnh của chính SKU thắng ảnh cấp Product")
    void skuImageWinsOverProductImage() {
        List<ProductMediaEntity> media = List.of(
                media(null, MediaType.IMAGE, "catalog/product.jpg", 0),
                media(skuId, MediaType.IMAGE, "catalog/sku.jpg", 9));

        assertThat(assembler.effectiveThumbnail(skuId, media)).isEqualTo("catalog/sku.jpg");
    }

    @Test
    @DisplayName("Trong cùng một cấp, sort_order nhỏ nhất thắng")
    void lowestSortOrderWinsWithinScope() {
        List<ProductMediaEntity> media = List.of(
                media(skuId, MediaType.IMAGE, "catalog/sku-second.jpg", 5),
                media(skuId, MediaType.IMAGE, "catalog/sku-first.jpg", 1));

        assertThat(assembler.effectiveThumbnail(skuId, media)).isEqualTo("catalog/sku-first.jpg");
    }

    @Test
    @DisplayName("SKU không có ảnh thì fallback ảnh cấp Product nhỏ nhất")
    void fallsBackToProductImage() {
        List<ProductMediaEntity> media = List.of(
                media(null, MediaType.IMAGE, "catalog/product-second.jpg", 4),
                media(null, MediaType.IMAGE, "catalog/product-first.jpg", 2));

        assertThat(assembler.effectiveThumbnail(skuId, media)).isEqualTo("catalog/product-first.jpg");
    }

    @Test
    @DisplayName("VIDEO không bao giờ được chọn, kể cả khi sort_order nhỏ nhất")
    void videoIsNeverSelected() {
        List<ProductMediaEntity> media = List.of(
                media(skuId, MediaType.VIDEO, "catalog/sku-clip.mp4", 0),
                media(null, MediaType.VIDEO, "catalog/product-clip.mp4", 0),
                media(null, MediaType.IMAGE, "catalog/product.jpg", 7));

        assertThat(assembler.effectiveThumbnail(skuId, media)).isEqualTo("catalog/product.jpg");
    }

    @Test
    @DisplayName("Không có ảnh hợp lệ nào thì trả null, không phải chuỗi rỗng")
    void resolvesToNullWithoutAnyImage() {
        assertThat(assembler.effectiveThumbnail(skuId, List.of())).isNull();
        assertThat(assembler.effectiveThumbnail(skuId,
                List.of(media(skuId, MediaType.VIDEO, "catalog/clip.mp4", 0)))).isNull();
    }

    @Test
    @DisplayName("Ảnh của SKU anh em không rò rỉ sang SKU đang resolve")
    void siblingSkuMediaIsIgnored() {
        List<ProductMediaEntity> media = List.of(
                media(otherSkuId, MediaType.IMAGE, "catalog/other-sku.jpg", 0),
                media(null, MediaType.IMAGE, "catalog/product.jpg", 3));

        assertThat(assembler.effectiveThumbnail(skuId, media)).isEqualTo("catalog/product.jpg");
        assertThat(assembler.effectiveThumbnail(otherSkuId, media)).isEqualTo("catalog/other-sku.jpg");
    }

    @Test
    @DisplayName("sort_order trùng nhau được tie-break ổn định theo media_id")
    void tiesAreBrokenByMediaId() {
        ProductMediaEntity first = media(skuId, MediaType.IMAGE, "catalog/a.jpg", 0);
        ProductMediaEntity second = media(skuId, MediaType.IMAGE, "catalog/b.jpg", 0);
        String expected = first.getMediaId().compareTo(second.getMediaId()) < 0
                ? "catalog/a.jpg"
                : "catalog/b.jpg";

        assertThat(assembler.effectiveThumbnail(skuId, List.of(first, second))).isEqualTo(expected);
        assertThat(assembler.effectiveThumbnail(skuId, List.of(second, first))).isEqualTo(expected);
    }

    /**
     * {@code eventMedia} cắt list ở 100 phần tử để khớp {@code maxItems} của
     * schema. Thumbnail phải được resolve từ media đầy đủ, nếu không một SKU có
     * 100 video riêng sẽ đẩy ảnh cấp Product ra ngoài và mất nhánh fallback.
     */
    @Test
    @DisplayName("Fallback vẫn đúng khi media của SKU vượt quá giới hạn của media[] trong event")
    void fallbackSurvivesEventMediaTruncation() {
        List<ProductMediaEntity> media = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            media.add(media(skuId, MediaType.VIDEO, "catalog/sku-clip-" + i + ".mp4", i));
        }
        media.add(media(null, MediaType.IMAGE, "catalog/product.jpg", 500));

        assertThat(assembler.eventMedia(skuId, media)).hasSize(100);
        assertThat(assembler.effectiveThumbnail(skuId, media)).isEqualTo("catalog/product.jpg");
    }

    @Test
    @DisplayName("Export và admin response mang đúng thumbnail đã resolve")
    void skuResponseCarriesResolvedThumbnail() {
        List<ProductMediaEntity> media = List.of(
                media(null, MediaType.IMAGE, "catalog/product.jpg", 0),
                media(skuId, MediaType.IMAGE, "catalog/sku.jpg", 1));

        ProductDtos.SkuResponse response = assembler.toSku(sku(), media);

        assertThat(response.thumbnailStorageKey()).isEqualTo("catalog/sku.jpg");
        // media[] của response chỉ là media của chính SKU; thumbnail thì không.
        assertThat(response.media()).hasSize(1);
    }

    private SkuEntity sku() {
        SkuEntity sku = new SkuEntity();
        sku.setSkuId(skuId);
        sku.setProductId(productId);
        sku.setSkuCode("PIXEL9-BLACK");
        sku.setBasePriceVnd(25_000_000L);
        sku.setSellingPriceVnd(25_000_000L);
        sku.setStatus(SkuStatus.ACTIVE);
        sku.setVariantAttributes(new LinkedHashMap<>());
        sku.setCreatedAt(NOW);
        sku.setUpdatedAt(NOW);
        return sku;
    }

    private ProductMediaEntity media(UUID owningSkuId, MediaType type, String storageKey, int sortOrder) {
        ProductMediaEntity media = new ProductMediaEntity();
        media.setMediaId(UUID.randomUUID());
        media.setProductId(productId);
        media.setSkuId(owningSkuId);
        media.setMediaType(type);
        media.setStorageKey(storageKey);
        media.setAltText(null);
        media.setSortOrder(sortOrder);
        media.setCreatedAt(NOW);
        media.setUpdatedAt(NOW);
        return media;
    }
}
