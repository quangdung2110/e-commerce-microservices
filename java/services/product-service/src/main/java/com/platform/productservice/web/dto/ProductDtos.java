package com.platform.productservice.web.dto;

import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.enums.SkuStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO của nhóm Product/SKU/media, ánh xạ 1-1 với schema trong
 * contracts/http/product/v1/openapi.yaml. Tên field JSON là snake_case theo
 * cấu hình Jackson toàn cục.
 *
 * <p>Client không bao giờ gửi {@code selling_price_vnd}: Product là nơi duy nhất
 * tính giá công bố (PRODUCT-BR-005).
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record MediaResponse(UUID mediaId,
                                UUID productId,
                                UUID skuId,
                                MediaType mediaType,
                                String storageKey,
                                String altText,
                                int sortOrder,
                                Instant createdAt,
                                Instant updatedAt) {
    }

    public record SkuResponse(UUID skuId,
                              UUID productId,
                              String skuCode,
                              long basePriceVnd,
                              long sellingPriceVnd,
                              SkuStatus status,
                              Map<String, Object> variantAttributes,
                              List<MediaResponse> media,
                              String thumbnailStorageKey,
                              Instant createdAt,
                              Instant updatedAt) {
    }

    public record ProductResponse(UUID productId,
                                  UUID categoryId,
                                  String name,
                                  String slug,
                                  String description,
                                  Map<String, Object> attributes,
                                  ProductStatus status,
                                  long catalogRevision,
                                  List<MediaResponse> media,
                                  List<SkuResponse> skus,
                                  Instant createdAt,
                                  Instant updatedAt) {
    }

    public record ProductSummaryResponse(UUID productId,
                                         UUID categoryId,
                                         String name,
                                         String slug,
                                         ProductStatus status,
                                         long catalogRevision,
                                         Instant updatedAt) {
    }

    public record ProductPageResponse(List<ProductSummaryResponse> items, String nextCursor) {
    }

    public record ProductExportPageResponse(List<ProductResponse> products, String nextCursor) {
    }

    public record CreateMediaInput(@NotNull MediaType mediaType,
                                   @NotBlank @Size(max = 1024) String storageKey,
                                   @Size(max = 255) String altText,
                                   @Min(0) Integer sortOrder) {
    }

    public record CreateSkuRequest(@NotBlank @Size(max = 64) String skuCode,
                                   @NotNull @Positive Long basePriceVnd,
                                   @NotNull SkuStatus status,
                                   @NotNull Map<String, Object> variantAttributes,
                                   @Valid @Size(max = 100) List<CreateMediaInput> media) {
    }

    public record ReplaceSkuRequest(@NotBlank @Size(max = 64) String skuCode,
                                    @NotNull @Positive Long basePriceVnd,
                                    @NotNull SkuStatus status,
                                    @NotNull Map<String, Object> variantAttributes) {
    }

    public record CreateProductRequest(@NotNull UUID categoryId,
                                       @NotBlank @Size(max = 255) String name,
                                       @NotBlank @Size(max = 180) String slug,
                                       String description,
                                       @NotNull Map<String, Object> attributes,
                                       @Valid @Size(max = 100) List<CreateMediaInput> media,
                                       @NotEmpty @Valid @Size(max = 500) List<CreateSkuRequest> skus) {
    }

    public record ReplaceProductRequest(@NotNull UUID categoryId,
                                        @NotBlank @Size(max = 255) String name,
                                        @NotBlank @Size(max = 180) String slug,
                                        String description,
                                        @NotNull Map<String, Object> attributes) {
    }

    /** DRAFT chỉ được tạo, không phải target của lifecycle transition. */
    public record ChangeProductStatusRequest(@NotNull ProductStatus status) {
    }

    public record CreateMediaRequest(UUID skuId,
                                     @NotNull MediaType mediaType,
                                     @NotBlank @Size(max = 1024) String storageKey,
                                     @Size(max = 255) String altText,
                                     @Min(0) Integer sortOrder) {
    }

    public record ReplaceMediaRequest(UUID skuId,
                                      @NotNull MediaType mediaType,
                                      @NotBlank @Size(max = 1024) String storageKey,
                                      @Size(max = 255) String altText,
                                      @NotNull @Min(0) Integer sortOrder) {
    }
}
