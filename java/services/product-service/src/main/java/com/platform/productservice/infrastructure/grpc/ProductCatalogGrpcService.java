package com.platform.productservice.infrastructure.grpc;

import com.ecommerce.contracts.common.v1.Money;
import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.GetSkuRequest;
import com.ecommerce.contracts.product.v1.GetSkuResponse;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.ecommerce.contracts.product.v1.Sku;
import com.ecommerce.contracts.product.v1.SkuNotFound;
import com.ecommerce.contracts.product.v1.SkuStatus;
import com.ecommerce.contracts.product.v1.SkuUnavailable;
import com.ecommerce.contracts.product.v1.SkuUnavailableReason;
import com.google.protobuf.Timestamp;
import com.platform.productservice.common.DomainException;
import com.platform.productservice.domain.entity.ProductEntity;
import com.platform.productservice.domain.entity.ProductMediaEntity;
import com.platform.productservice.domain.entity.SkuEntity;
import com.platform.productservice.domain.enums.MediaType;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.infrastructure.persistence.ProductMediaRepository;
import com.platform.productservice.infrastructure.persistence.ProductRepository;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.service.support.CategoryTreeService;
import com.platform.productservice.service.support.VariantNameResolver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalog point-read cho Cart và Checkout (WF-PRODUCT-004).
 *
 * <p>Response mang catalog saleability và giá công bố, không mang quantity,
 * stock status hay {@code OUT_OF_STOCK}. Cart có thể giữ SKU hết hàng; quyết
 * định cuối cùng về tồn kho là reservation tại Inventory.
 */
@Service
@RequiredArgsConstructor
public class ProductCatalogGrpcService extends ProductServiceGrpc.ProductServiceImplBase {

    private static final String CURRENCY_VND = "VND";
    private static final int MAX_BATCH_SIZE = 100;
    /** Contract bound of {@code Sku.category_ids}; Promotion rules and the Order snapshot share it. */
    private static final int MAX_CATEGORY_IDS = 20;

    private static final Comparator<ProductMediaEntity> MEDIA_ORDER =
            Comparator.comparingInt(ProductMediaEntity::getSortOrder)
                    .thenComparing(ProductMediaEntity::getMediaId);

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final ProductMediaRepository mediaRepository;
    private final CategoryTreeService categoryTree;
    private final VariantNameResolver variantNames;

    @Override
    @Transactional(readOnly = true)
    public void getSku(GetSkuRequest request, StreamObserver<GetSkuResponse> responseObserver) {
        UUID skuId;
        try {
            skuId = UUID.fromString(request.getSkuId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(ProductGrpcErrors.exception(ProductGrpcError.SKU_ID_INVALID,
                    "sku_id must be a UUID", Map.of(), "sku_id"));
            return;
        }

        Optional<SkuEntity> sku = skuRepository.findById(skuId);
        if (sku.isEmpty()) {
            responseObserver.onNext(GetSkuResponse.newBuilder()
                    .setNotFound(SkuNotFound.newBuilder().setSkuId(request.getSkuId()).build())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        ProductEntity product = productRepository.findById(sku.get().getProductId()).orElse(null);
        if (product == null) {
            responseObserver.onNext(GetSkuResponse.newBuilder()
                    .setNotFound(SkuNotFound.newBuilder().setSkuId(request.getSkuId()).build())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        GetSkuResponse.Builder response = GetSkuResponse.newBuilder();
        if (!catalogActive(product, sku.get())) {
            response.setUnavailable(SkuUnavailable.newBuilder()
                    .setSkuId(request.getSkuId())
                    .setReason(SkuUnavailableReason.SKU_UNAVAILABLE_REASON_INACTIVE)
                    .build());
        } else {
            List<ProductMediaEntity> media =
                    mediaRepository.findByProductIdOrderBySortOrderAscMediaIdAsc(product.getProductId());
            response.setSku(toProto(product, sku.get(), media, categoryIds(product, new HashMap<>()),
                    variantNames.resolve(List.of(sku.get())).getOrDefault(skuId, "")));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void getBatchSkus(GetBatchSkusRequest request,
                             StreamObserver<GetBatchSkusResponse> responseObserver) {
        Set<String> requestedIds = new LinkedHashSet<>(request.getSkuIdsList());
        if (requestedIds.size() > MAX_BATCH_SIZE) {
            responseObserver.onError(ProductGrpcErrors.exception(ProductGrpcError.BATCH_TOO_LARGE,
                    "at most " + MAX_BATCH_SIZE + " sku_ids per request",
                    Map.of("max_batch_size", String.valueOf(MAX_BATCH_SIZE)), "sku_ids"));
            return;
        }

        GetBatchSkusResponse.Builder response = GetBatchSkusResponse.newBuilder();
        List<UUID> skuIds = new java.util.ArrayList<>();
        for (String raw : requestedIds) {
            try {
                skuIds.add(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                responseObserver.onError(ProductGrpcErrors.exception(ProductGrpcError.SKU_ID_INVALID,
                        "sku_id must be a UUID: " + raw, Map.of(), "sku_ids"));
                return;
            }
        }

        Map<UUID, SkuEntity> skus = skuRepository.findAllById(skuIds).stream()
                .collect(Collectors.toMap(SkuEntity::getSkuId, Function.identity()));
        Set<UUID> productIds = skus.values().stream()
                .map(SkuEntity::getProductId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, ProductEntity> products = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductEntity::getProductId, Function.identity()));
        Map<UUID, List<ProductMediaEntity>> mediaByProduct = productIds.isEmpty()
                ? Map.of()
                : mediaRepository.findByProductIdInOrderBySortOrderAscMediaIdAsc(productIds).stream()
                        .collect(Collectors.groupingBy(ProductMediaEntity::getProductId));

        Map<UUID, String> names = variantNames.resolve(skus.values());
        Map<UUID, List<String>> ancestry = new HashMap<>();
        for (UUID skuId : skuIds) {
            SkuEntity sku = skus.get(skuId);
            ProductEntity product = sku == null ? null : products.get(sku.getProductId());
            if (sku == null || product == null) {
                response.addNotFoundSkuIds(skuId.toString());
            } else if (!catalogActive(product, sku)) {
                response.addUnavailableSkuIds(skuId.toString());
            } else {
                response.putSkus(skuId.toString(), toProto(product, sku,
                        mediaByProduct.getOrDefault(product.getProductId(), List.of()),
                        categoryIds(product, ancestry), names.getOrDefault(skuId, "")));
            }
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    /** Catalog availability = Product ACTIVE và SKU ACTIVE; không liên quan tồn kho. */
    private boolean catalogActive(ProductEntity product, SkuEntity sku) {
        return product.getStatus() == ProductStatus.ACTIVE
                && sku.getStatus() == com.platform.productservice.domain.enums.SkuStatus.ACTIVE;
    }

    private Sku toProto(ProductEntity product, SkuEntity sku, List<ProductMediaEntity> media,
                         List<String> categoryIds, String variantName) {
        Sku.Builder builder = Sku.newBuilder()
                .setSkuId(sku.getSkuId().toString())
                .setProductId(product.getProductId().toString())
                .setProductName(product.getName())
                .setVariantName(variantName)
                .setPrice(money(sku.getSellingPriceVnd()))
                .setBasePrice(money(sku.getBasePriceVnd()))
                .setStatus(sku.getStatus() == com.platform.productservice.domain.enums.SkuStatus.ACTIVE
                        ? SkuStatus.SKU_STATUS_ACTIVE
                        : SkuStatus.SKU_STATUS_INACTIVE)
                .setCatalogRevision(product.getCatalogRevision())
                .setUpdatedAt(timestamp(sku.getUpdatedAt()))
                .addAllCategoryIds(categoryIds);

        sku.getVariantAttributes()
                .forEach((key, value) -> builder.putVariantAttributes(key, String.valueOf(value)));
        imageReference(sku.getSkuId(), media).ifPresent(builder::setImageUrl);
        return builder.build();
    }

    /**
     * Product-owned ancestry used by trusted Checkout promotion facts: the product's category and every
     * ancestor up to the root, root first. Computed once per category per call. A path longer than the
     * contract bound keeps the categories closest to the product, because those are the ones rules name.
     * A category that cannot be resolved yields no ids rather than failing the whole read.
     */
    private List<String> categoryIds(ProductEntity product, Map<UUID, List<String>> cache) {
        return cache.computeIfAbsent(product.getCategoryId(), categoryId -> {
            try {
                List<String> path = categoryTree.path(categoryTree.require(categoryId)).stream()
                        .map(category -> category.getCategoryId().toString())
                        .toList();
                return path.size() <= MAX_CATEGORY_IDS
                        ? path
                        : path.subList(path.size() - MAX_CATEGORY_IDS, path.size());
            } catch (DomainException e) {
                return List.of();
            }
        });
    }

    /** Ảnh của chính SKU nếu có, ngược lại ảnh cấp Product. */
    private Optional<String> imageReference(UUID skuId, List<ProductMediaEntity> media) {
        Optional<String> own = media.stream()
                .filter(item -> item.getMediaType() == MediaType.IMAGE && skuId.equals(item.getSkuId()))
                .min(MEDIA_ORDER)
                .map(ProductMediaEntity::getStorageKey);
        if (own.isPresent()) {
            return own;
        }
        return media.stream()
                .filter(item -> item.getMediaType() == MediaType.IMAGE && item.getSkuId() == null)
                .min(MEDIA_ORDER)
                .map(ProductMediaEntity::getStorageKey);
    }

    private Money money(long amountVnd) {
        return Money.newBuilder().setAmountMinor(amountVnd).setCurrency(CURRENCY_VND).build();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
