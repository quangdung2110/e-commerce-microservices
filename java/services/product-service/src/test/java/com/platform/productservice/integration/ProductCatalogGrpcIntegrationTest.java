package com.platform.productservice.integration;

import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.GetSkuRequest;
import com.ecommerce.contracts.product.v1.GetSkuResponse;
import com.ecommerce.contracts.product.v1.SkuStatus;
import com.ecommerce.contracts.product.v1.SkuUnavailableReason;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.infrastructure.grpc.ProductCatalogGrpcService;
import com.platform.productservice.infrastructure.kafka.MarkdownSnapshotConsumer;
import com.platform.productservice.web.dto.ProductDtos;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-PRODUCT-004: catalog point-read cho Cart/Checkout. Response mang giá và
 * catalog saleability, không bao giờ mang dữ liệu tồn kho.
 */
class ProductCatalogGrpcIntegrationTest extends AbstractCatalogIntegrationTest {

    @Test
    void variantNameUsesCurrentAttributeAndOptionLabelsOnPointAndBatchReads() {
        var type = com.platform.productservice.domain.enums.AttributeValueType.SELECT;
        UUID category = givenLeafCategory("variant-labels");
        UUID color = givenAttribute("color", "Màu sắc", type);
        givenMapping(category, color, true, true);
        attributeService.createOption(color, new com.platform.productservice.web.dto.AttributeDtos.CreateAttributeOptionRequest("black", "Đen", 0));
        var product = productCommandService.create(new ProductDtos.CreateProductRequest(category,
                "Label test", "label-test", null, Map.of(), List.of(),
                List.of(new ProductDtos.CreateSkuRequest("LABEL-1", 100L,
                        com.platform.productservice.domain.enums.SkuStatus.ACTIVE,
                        Map.of("color", "black"), List.of()))));
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(1L), ProductStatus.ACTIVE);
        String sku = product.skus().get(0).skuId().toString();
        assertThat(getSku(sku).getSku().getVariantName()).isEqualTo("Màu sắc: Đen");

        attributeService.replace(color, new com.platform.productservice.web.dto.AttributeDtos.ReplaceAttributeRequest("color", "Màu mới", type));
        assertThat(getSku(sku).getSku().getVariantName()).isEqualTo("Màu mới: Đen");
        CapturingObserver<GetBatchSkusResponse> observer = new CapturingObserver<>();
        grpcService.getBatchSkus(GetBatchSkusRequest.newBuilder().addSkuIds(sku).build(), observer);
        assertThat(observer.error).isNull();
        assertThat(observer.value.getSkusOrThrow(sku).getVariantName()).isEqualTo("Màu mới: Đen");
        assertThat(observer.value.getSkusOrThrow(sku).getVariantAttributesMap()).containsEntry("color", "black");
    }

    @Autowired
    private ProductCatalogGrpcService grpcService;

    @Autowired
    private MarkdownSnapshotConsumer markdownConsumer;

    @Test
    @DisplayName("GetSku trả base price, selling price và catalog_revision")
    void getSkuReturnsCatalogSnapshot() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = productCommandService.create(
                new ProductDtos.CreateProductRequest(categoryId, "Pixel 9", "pixel-9", null, Map.of(),
                        List.of(new ProductDtos.CreateMediaInput(MediaType.IMAGE,
                                "catalog/pixel/main.jpg", null, 0)),
                        List.of(new ProductDtos.CreateSkuRequest("P9-BLACK", 25_000_000L,
                                com.platform.productservice.domain.enums.SkuStatus.ACTIVE,
                                Map.of(), List.of()))));
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(1L), ProductStatus.ACTIVE);
        UUID skuId = product.skus().get(0).skuId();

        GetSkuResponse response = getSku(skuId.toString());

        assertThat(response.hasSku()).isTrue();
        assertThat(response.getSku().getPrice().getAmountMinor()).isEqualTo(25_000_000L);
        assertThat(response.getSku().getPrice().getCurrency()).isEqualTo("VND");
        assertThat(response.getSku().getBasePrice().getAmountMinor()).isEqualTo(25_000_000L);
        assertThat(response.getSku().getCatalogRevision()).isEqualTo(2L);
        assertThat(response.getSku().getStatus()).isEqualTo(SkuStatus.SKU_STATUS_ACTIVE);
        assertThat(response.getSku().getImageUrl()).isEqualTo("catalog/pixel/main.jpg");
        assertThat(response.getSku().getProductName()).isEqualTo("Pixel 9");
    }

    @Test
    @DisplayName("Giá trả về là giá công bố sau markdown")
    void getSkuReturnsMarkdownPrice() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 25_000_000L);
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(1L), ProductStatus.ACTIVE);
        markdownConsumer.onMessage(activeSnapshot(product.productId()));

        GetSkuResponse response = getSku(product.skus().get(0).skuId().toString());

        assertThat(response.getSku().getPrice().getAmountMinor()).isEqualTo(22_500_000L);
        assertThat(response.getSku().getBasePrice().getAmountMinor()).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("Product chưa ACTIVE là unavailable, không phải transport error")
    void draftProductIsUnavailable() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse product = givenProduct(categoryId, "pixel", 1000L);

        GetSkuResponse response = getSku(product.skus().get(0).skuId().toString());

        assertThat(response.hasUnavailable()).isTrue();
        assertThat(response.getUnavailable().getReason())
                .isEqualTo(SkuUnavailableReason.SKU_UNAVAILABLE_REASON_INACTIVE);
    }

    @Test
    @DisplayName("SKU không tồn tại là business result not_found")
    void unknownSkuIsNotFound() {
        GetSkuResponse response = getSku(UUID.randomUUID().toString());

        assertThat(response.hasNotFound()).isTrue();
    }

    @Test
    @DisplayName("sku_id không phải UUID trả INVALID_ARGUMENT")
    void invalidSkuIdIsRejected() {
        CapturingObserver<GetSkuResponse> observer = new CapturingObserver<>();

        grpcService.getSku(GetSkuRequest.newBuilder().setSkuId("nope").build(), observer);

        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("GetBatchSkus tách rõ found, not_found và unavailable")
    void batchSeparatesResults() {
        UUID categoryId = givenLeafCategory("phones");
        ProductDtos.ProductResponse active = givenProduct(categoryId, "active", 1000L);
        productCommandService.changeStatus(active.productId(), ProductEntity.etagOf(1L), ProductStatus.ACTIVE);
        ProductDtos.ProductResponse draft = givenProduct(categoryId, "draft", 2000L);
        String missingId = UUID.randomUUID().toString();

        CapturingObserver<GetBatchSkusResponse> observer = new CapturingObserver<>();
        grpcService.getBatchSkus(GetBatchSkusRequest.newBuilder()
                .addSkuIds(active.skus().get(0).skuId().toString())
                .addSkuIds(draft.skus().get(0).skuId().toString())
                .addSkuIds(missingId)
                .build(), observer);

        GetBatchSkusResponse response = observer.value;
        assertThat(response.getSkusMap()).containsOnlyKeys(active.skus().get(0).skuId().toString());
        assertThat(response.getUnavailableSkuIdsList())
                .containsExactly(draft.skus().get(0).skuId().toString());
        assertThat(response.getNotFoundSkuIdsList()).containsExactly(missingId);
    }

    @Test
    @DisplayName("category_ids là ancestry Product-owned: category của product và mọi ancestor, root trước")
    void returnsCategoryAncestryRootFirst() {
        UUID root = givenLeafCategory("electronics");
        UUID middle = givenChildCategory(root, "phones");
        UUID leaf = givenChildCategory(middle, "flagship");
        ProductDtos.ProductResponse product = givenProduct(leaf, "pixel-flagship", 25_000_000L);
        productCommandService.changeStatus(product.productId(), ProductEntity.etagOf(1L), ProductStatus.ACTIVE);
        String skuId = product.skus().get(0).skuId().toString();

        CapturingObserver<GetBatchSkusResponse> batch = new CapturingObserver<>();
        grpcService.getBatchSkus(GetBatchSkusRequest.newBuilder().addSkuIds(skuId).build(), batch);

        assertThat(batch.value.getSkusOrThrow(skuId).getCategoryIdsList())
                .containsExactly(root.toString(), middle.toString(), leaf.toString());
        assertThat(getSku(skuId).getSku().getCategoryIdsList())
                .containsExactly(root.toString(), middle.toString(), leaf.toString());
    }

    @Test
    @DisplayName("Batch quá 100 SKU bị từ chối")
    void batchSizeIsCapped() {
        GetBatchSkusRequest.Builder request = GetBatchSkusRequest.newBuilder();
        IntStream.range(0, 101).forEach(index -> request.addSkuIds(UUID.randomUUID().toString()));

        CapturingObserver<GetBatchSkusResponse> observer = new CapturingObserver<>();
        grpcService.getBatchSkus(request.build(), observer);

        assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private GetSkuResponse getSku(String skuId) {
        CapturingObserver<GetSkuResponse> observer = new CapturingObserver<>();
        grpcService.getSku(GetSkuRequest.newBuilder().setSkuId(skuId).build(), observer);
        assertThat(observer.error).isNull();
        return observer.value;
    }

    private String activeSnapshot(UUID productId) {
        return """
                {
                  "specversion": "1.0", "id": "%s",
                  "type": "com.ecommerce.promotion.product-markdown-snapshot.v1",
                  "source": "/services/promotion", "subject": "products/%s",
                  "time": "%s", "datacontenttype": "application/json", "dataschema": "x",
                  "sequence": 1,
                  "data": {
                    "product_id": "%s", "promotion_rule_id": 1, "source_revision": 1,
                    "state": "ACTIVE", "discount": {"type": "PERCENTAGE", "discount_bps": 1000},
                    "starts_at": "%s", "ends_at": "%s"
                  }
                }
                """.formatted(UUID.randomUUID(), productId, java.time.Instant.now(), productId,
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().plusSeconds(86_400));
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {

        private final List<T> values = new ArrayList<>();
        private T value;
        private Throwable error;

        @Override
        public void onNext(T value) {
            this.value = value;
            this.values.add(value);
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
            // Không cần theo dõi completion trong test đồng bộ.
        }
    }
}
