package com.platform.productservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.productservice.common.DomainException;
import com.platform.productservice.common.ErrorCode;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuStatus;
import com.platform.productservice.service.ProductQueryService;
import com.platform.productservice.web.dto.ProductDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Buyer-read nội bộ cho Product Detail BFF (ADR-015, WF-PRODUCT-007).
 *
 * <p>Bài test then chốt là những gì endpoint không trả: catalog chưa công bố và
 * mọi field tồn kho. Availability là read composition của BFF, không phải dữ
 * liệu Product sở hữu (ADR-009).
 */
class BuyerProductDetailIntegrationTest extends AbstractCatalogIntegrationTest {

    /** Từ khóa của tồn kho; không field nào trong response được chứa chúng. */
    private static final List<String> STOCK_MARKERS =
            List.of("stock", "quantity", "availab", "reserv", "on_hand", "observed_at");

    @Autowired
    private ProductQueryService productQueryService;

    private UUID categoryId;

    @BeforeEach
    void givenCategory() {
        categoryId = givenLeafCategory("phones");
    }

    @Test
    @DisplayName("Product ACTIVE đọc được theo slug kèm giá công bố")
    void activeProductIsReadableBySlug() {
        ProductDtos.ProductResponse created = givenActiveProduct("pixel", 25_000_000L);

        ProductDtos.ProductResponse detail = productQueryService.getBuyerDetail("pixel");

        assertThat(detail.productId()).isEqualTo(created.productId());
        assertThat(detail.slug()).isEqualTo("pixel");
        assertThat(detail.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(detail.skus()).hasSize(1);
        assertThat(detail.skus().get(0).basePriceVnd()).isEqualTo(25_000_000L);
        assertThat(detail.skus().get(0).sellingPriceVnd()).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("Product DRAFT không lộ ra buyer-read")
    void draftProductIsNotVisible() {
        givenProduct(categoryId, "unreleased", 1_000L);

        assertThatThrownBy(() -> productQueryService.getBuyerDetail("unreleased"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("Product HIDDEN không lộ ra buyer-read")
    void hiddenProductIsNotVisible() {
        ProductDtos.ProductResponse product = givenActiveProduct("recalled", 1_000L);
        productCommandService.changeStatus(
                product.productId(), etagOf(product.productId()), ProductStatus.HIDDEN);

        assertThatThrownBy(() -> productQueryService.getBuyerDetail("recalled"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("Slug không tồn tại và slug chưa công bố trả cùng một lỗi")
    void unknownSlugIsNotFound() {
        assertThatThrownBy(() -> productQueryService.getBuyerDetail("no-such-slug"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("SKU INACTIVE bị lọc khỏi buyer-read")
    void inactiveSkusAreFiltered() {
        ProductDtos.ProductResponse product = givenActiveProduct("laptop", 30_000_000L);
        UUID productId = product.productId();
        UUID retiredSkuId = skuCommandService.create(productId, etagOf(productId),
                new ProductDtos.CreateSkuRequest("LAPTOP-2", 31_000_000L,
                        SkuStatus.ACTIVE, Map.of(), List.of())).body().skuId();
        skuCommandService.replace(productId, retiredSkuId, etagOf(productId),
                new ProductDtos.ReplaceSkuRequest("LAPTOP-2", 31_000_000L,
                        SkuStatus.INACTIVE, Map.of()));

        ProductDtos.ProductResponse detail = productQueryService.getBuyerDetail("laptop");

        assertThat(detail.skus()).hasSize(1);
        assertThat(detail.skus().get(0).skuId()).isNotEqualTo(retiredSkuId);
        assertThat(detail.skus()).allMatch(sku -> sku.status() == SkuStatus.ACTIVE);
    }

    @Test
    @DisplayName("Product ACTIVE không còn SKU saleable trả not found")
    void productWithoutSaleableSkuIsNotFound() {
        ProductDtos.ProductResponse product = givenActiveProduct("sold-out-forever", 500L);
        UUID productId = product.productId();
        ProductDtos.SkuResponse onlySku = product.skus().get(0);
        skuCommandService.replace(productId, onlySku.skuId(), etagOf(productId),
                new ProductDtos.ReplaceSkuRequest(onlySku.skuCode(), 500L,
                        SkuStatus.INACTIVE, Map.of()));

        // Product vẫn ACTIVE trong catalog quản trị; chỉ buyer-read từ chối nó.
        assertThat(productRepository.findById(productId).orElseThrow().getStatus())
                .isEqualTo(ProductStatus.ACTIVE);
        assertThatThrownBy(() -> productQueryService.getBuyerDetail("sold-out-forever"))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("Buyer-read mang media Product, media SKU và thumbnail đã resolve")
    void mediaIsCarriedWithResolvedThumbnail() {
        ProductDtos.ProductResponse product = givenActiveProduct("camera", 12_000_000L);
        UUID productId = product.productId();
        UUID skuId = product.skus().get(0).skuId();
        mediaCommandService.create(productId, etagOf(productId), new ProductDtos.CreateMediaRequest(
                null, MediaType.IMAGE, "products/camera/hero.jpg", "hero", 0));
        mediaCommandService.create(productId, etagOf(productId), new ProductDtos.CreateMediaRequest(
                skuId, MediaType.IMAGE, "skus/camera-1/front.jpg", "front", 0));

        ProductDtos.ProductResponse detail = productQueryService.getBuyerDetail("camera");

        assertThat(detail.media()).extracting(ProductDtos.MediaResponse::storageKey)
                .containsExactly("products/camera/hero.jpg");
        ProductDtos.SkuResponse sku = detail.skus().get(0);
        assertThat(sku.media()).extracting(ProductDtos.MediaResponse::storageKey)
                .containsExactly("skus/camera-1/front.jpg");
        // Ảnh của chính SKU thắng ảnh cấp Product (PRODUCT-BR-012, ADR-013).
        assertThat(sku.thumbnailStorageKey()).isEqualTo("skus/camera-1/front.jpg");
    }

    @Test
    @DisplayName("Buyer-read mang variant attribute của từng SKU")
    void variantAttributesAreCarried() {
        UUID colorId = givenAttribute("color");
        givenMapping(categoryId, colorId, true, true);
        ProductDtos.ProductResponse created = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "tee", "tee", null,
                        Map.of(), List.of(),
                        List.of(new ProductDtos.CreateSkuRequest("TEE-RED", 200_000L,
                                SkuStatus.ACTIVE, Map.of("color", "red"), List.of()))));
        productCommandService.changeStatus(
                created.productId(), etagOf(created.productId()), ProductStatus.ACTIVE);

        ProductDtos.ProductResponse detail = productQueryService.getBuyerDetail("tee");

        assertThat(detail.skus().get(0).variantAttributes()).containsEntry("color", "red");
    }

    @Test
    @DisplayName("Response buyer-read không chứa bất kỳ field tồn kho nào")
    void responseCarriesNoStockField() {
        ProductDtos.ProductResponse product = givenActiveProduct("tablet", 9_000_000L);
        UUID productId = product.productId();
        mediaCommandService.create(productId, etagOf(productId), new ProductDtos.CreateMediaRequest(
                null, MediaType.IMAGE, "products/tablet/hero.jpg", "hero", 0));

        JsonNode json = objectMapper.valueToTree(productQueryService.getBuyerDetail("tablet"));

        assertThat(fieldNames(json)).isNotEmpty().allSatisfy(name ->
                assertThat(STOCK_MARKERS).noneMatch(name::contains));
    }

    // ---------------------------------------------------------------- fixtures

    /** Product ACTIVE một SKU ACTIVE — trạng thái duy nhất buyer-read chấp nhận. */
    private ProductDtos.ProductResponse givenActiveProduct(String slug, long basePriceVnd) {
        ProductDtos.ProductResponse product = givenProduct(categoryId, slug, basePriceVnd);
        return productCommandService.changeStatus(
                product.productId(), etagOf(product.productId()), ProductStatus.ACTIVE);
    }

    /** Mọi tên field trong cây JSON, đã lowercase, kể cả field lồng trong sku/media. */
    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        collectFieldNames(node, names);
        return names;
    }

    private void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey().toLowerCase(Locale.ROOT));
                collectFieldNames(entry.getValue(), names);
            });
            return;
        }
        if (node.isArray()) {
            node.forEach(element -> collectFieldNames(element, names));
        }
    }
}
