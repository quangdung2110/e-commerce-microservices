package com.platform.productservice.infrastructure.grpc;

import com.platform.productservice.domain.entity.Category;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.dto.grpc.GrpcProtoMapper;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.inbound.CategoryServicePort;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.proto.*;
import com.platform.productservice.service.ProductSkuReadService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * gRPC service implementation for internal service-to-service communication.
 * Provides high-performance point-read operations for Cart, Order, Checkout, and other internal services.
 * <p>
 * Uses cache-aside pattern with Probabilistic Early Expiration (PER) for optimal performance.
 */
@GrpcService
public class ProductSkuGrpcService extends ProductGrpcServiceGrpc.ProductGrpcServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ProductSkuGrpcService.class);

    private final ProductSkuReadService productSkuReadService;
    private final CategoryServicePort categoryService;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final GrpcProtoMapper protoMapper;

    @Autowired
    public ProductSkuGrpcService(
            ProductSkuReadService productSkuReadService,
            CategoryServicePort categoryService,
            CategoryRepository categoryRepository,
            ProductRepository productRepository,
            GrpcProtoMapper protoMapper) {
        this.productSkuReadService = productSkuReadService;
        this.categoryService = categoryService;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.protoMapper = protoMapper;
    }

    @Override
    public void getProductSku(ProductSkuRequest request, StreamObserver<ProductSkuResponse> responseObserver) {
        String skuId = request.getSkuId();
        log.debug("gRPC GetProductSku: skuId={}", skuId);

        try {
            if (skuId == null || skuId.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("sku_id must not be blank")
                        .asRuntimeException());
                return;
            }

            Optional<SkuCacheEntry> cacheEntry = productSkuReadService.getById(skuId);

            if (cacheEntry.isEmpty()) {
                log.debug("gRPC GetProductSku: SKU not found skuId={}", skuId);
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("SKU not found: " + skuId)
                        .asRuntimeException());
                return;
            }

            ProductSkuResponse response = protoMapper.toProto(cacheEntry.get());
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("gRPC GetProductSku error: skuId={}", skuId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while fetching SKU")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getBatchProductSkus(BatchProductSkuRequest request, StreamObserver<BatchProductSkuResponse> responseObserver) {
        List<String> skuIds = request.getSkuIdsList();
        log.debug("gRPC GetBatchProductSkus: count={}", skuIds.size());

        try {
            if (skuIds == null || skuIds.isEmpty()) {
                responseObserver.onNext(BatchProductSkuResponse.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }

            Map<String, SkuCacheEntry> entries = productSkuReadService.getByIds(skuIds);

            BatchProductSkuResponse.Builder responseBuilder = BatchProductSkuResponse.newBuilder();
            for (String skuId : skuIds) {
                SkuCacheEntry entry = entries.get(skuId);
                if (entry != null) {
                    responseBuilder.addSkus(protoMapper.toProto(entry));
                } else {
                    ProductSkuResponse emptySku = ProductSkuResponse.newBuilder()
                            .setSkuId(skuId)
                            .build();
                    responseBuilder.addSkus(emptySku);
                }
            }

            ProductSkuResponse response = responseBuilder.build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("gRPC GetBatchProductSkus error: count={}", skuIds.size(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while fetching batch SKUs")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getCategoryTree(CategoryTreeSnapshotRequest request, StreamObserver<CategoryTreeSnapshotResponse> responseObserver) {
        log.debug("gRPC GetCategoryTree: includeDisabled={}", request.getIncludeDisabled());

        try {
            List<Category> rootCategories = categoryService.findTree();
            Map<String, List<Category>> childrenMap = buildChildrenMap(rootCategories);

            CategoryTreeSnapshotResponse response = buildCategoryTreeProto(rootCategories, childrenMap);
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("gRPC GetCategoryTree error", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while fetching category tree")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void getProductSnapshot(ProductSnapshotRequest request, StreamObserver<ProductSnapshotResponse> responseObserver) {
        String productId = request.getProductId();
        log.debug("gRPC GetProductSnapshot: productId={}, includeSkus={}, includeMedia={}",
                productId, request.getIncludeSkus(), request.getIncludeMedia());

        try {
            if (productId == null || productId.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("product_id must not be blank")
                        .asRuntimeException());
                return;
            }

            Optional<Product> productOpt = productRepository.findById(ProductId.of(productId));
            if (productOpt.isEmpty()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Product not found: " + productId)
                        .asRuntimeException());
                return;
            }

            Product product = productOpt.get();
            ProductSnapshotResponse.Builder builder = ProductSnapshotResponse.newBuilder()
                    .setProductId(productId)
                    .setProductName(product.getName() != null ? product.getName() : "")
                    .setSlug(product.getSlug() != null ? product.getSlug() : "")
                    .setDescription(product.getDescription() != null ? product.getDescription() : "")
                    .setCategoryId(product.getCategoryId() != null ? product.getCategoryId().getValue() : "")
                    .setStatus(product.getStatus() != null ? product.getStatus().name() : "")
                    .setIsDeleted(product.isDeleted())
                    .setCreatedAt(product.getCreatedAt() != null ? product.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : 0)
                    .setUpdatedAt(product.getUpdatedAt() != null ? product.getUpdatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : 0);

            if (request.getIncludeSkus() && product.getSkus() != null) {
                for (Sku sku : product.getSkus()) {
                    if (!sku.isDeleted()) {
                        builder.addSkus(protoMapper.toProto(sku, product));
                    }
                }
            }

            if (request.getIncludeMedia() && product.getMedia() != null) {
                for (var media : product.getMedia()) {
                    builder.addMedia(com.platform.productservice.proto.MediaResponse.newBuilder()
                            .setMediaId(media.getId() != null ? media.getId().getValue() : "")
                            .setMediaType(media.getMediaType() != null ? media.getMediaType().name() : "")
                            .setUrl(media.getUrl() != null ? media.getUrl() : "")
                            .setThumbnailUrl(media.getThumbnailUrl() != null ? media.getThumbnailUrl() : "")
                            .setSortOrder(media.getSortOrder() != null ? media.getSortOrder() : 0)
                            .build());
                }
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("gRPC GetProductSnapshot error: productId={}", productId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while fetching product snapshot")
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    private Map<String, List<Category>> buildChildrenMap(List<Category> rootCategories) {
        Map<String, List<Category>> childrenMap = new HashMap<>();
        for (Category root : rootCategories) {
            buildChildrenMapRecursive(root.getId().getValue(), childrenMap);
        }
        return childrenMap;
    }

    private void buildChildrenMapRecursive(String parentId, Map<String, List<Category>> childrenMap) {
        List<Category> children = categoryRepository.findChildren(CategoryId.of(parentId));
        if (!children.isEmpty()) {
            childrenMap.put(parentId, children);
            for (Category child : children) {
                buildChildrenMapRecursive(child.getId().getValue(), childrenMap);
            }
        }
    }

    private CategoryTreeSnapshotResponse buildCategoryTreeProto(
            List<Category> rootCategories, Map<String, List<Category>> childrenMap) {

        CategoryTreeSnapshotResponse.Builder builder = CategoryTreeSnapshotResponse.newBuilder()
                .setSnapshotTimestamp(System.currentTimeMillis());

        for (Category root : rootCategories) {
            CategoryNode node = buildCategoryNodeRecursive(root, childrenMap);
            builder.addRootCategories(node);
        }

        return builder.build();
    }

    private CategoryNode buildCategoryNodeRecursive(Category category, Map<String, List<Category>> childrenMap) {
        CategoryNode.Builder builder = CategoryNode.newBuilder()
                .setCategoryId(category.getId() != null ? category.getId().getValue() : "")
                .setParentId(category.getParentId() != null ? category.getParentId().getValue() : "")
                .setName(category.getName() != null ? category.getName() : "")
                .setSlug(category.getSlug() != null ? category.getSlug() : "")
                .setLevel(category.getLevel())
                .setIsLeaf(category.isLeaf());

        String categoryId = category.getId() != null ? category.getId().getValue() : "";
        List<Category> children = childrenMap.get(categoryId);
        if (children != null && !children.isEmpty()) {
            for (Category child : children) {
                CategoryNode childNode = buildCategoryNodeRecursive(child, childrenMap);
                builder.addChildren(childNode);
            }
        }

        return builder.build();
    }
}
