package com.platform.productservice.infrastructure.grpc;

import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.proto.*;
import com.platform.productservice.service.ProductSkuReadService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Optional;

/**
 * gRPC service for Cart Service integration.
 * Implements the Cart Service contract: GetSku / GetBatchSkus.
 * Uses cache-aside pattern with PER for optimal read performance.
 */
@GrpcService
public class CartServiceGrpcService extends CartServiceGrpcGrpc.CartServiceGrpcImplBase {

    private static final Logger log = LoggerFactory.getLogger(CartServiceGrpcService.class);

    private final ProductSkuReadService productSkuReadService;

    @Autowired
    public CartServiceGrpcService(ProductSkuReadService productSkuReadService) {
        this.productSkuReadService = productSkuReadService;
    }

    @Override
    public void getSku(GetSkuRequest request, StreamObserver<GetSkuResponse> responseObserver) {
        String skuId = request.getSkuId();
        log.debug("gRPC GetSku: skuId={}", skuId);

        try {
            if (skuId == null || skuId.isBlank()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("sku_id must not be blank")
                        .asRuntimeException());
                return;
            }

            Optional<SkuCacheEntry> cacheEntry = productSkuReadService.getById(skuId);

            if (cacheEntry.isEmpty()) {
                log.debug("gRPC GetSku: SKU not found skuId={}", skuId);
                GetSkuResponse response = GetSkuResponse.newBuilder()
                        .setNotFound(SkuNotFound.newBuilder().setSkuId(skuId).build())
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            SkuCacheEntry entry = cacheEntry.get();

            if (!entry.isAvailable() || entry.isDeleted()) {
                GetSkuResponse response = GetSkuResponse.newBuilder()
                        .setUnavailable(SkuUnavailable.newBuilder()
                                .setSkuId(skuId)
                                .setReason("SKU is unavailable or deleted")
                                .build())
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            SkuData skuData = toSkuData(entry);
            GetSkuResponse response = GetSkuResponse.newBuilder()
                    .setSku(skuData)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("gRPC GetSku error: skuId={}", skuId, e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while fetching SKU: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    @Override
    public void getBatchSkus(GetBatchSkusRequest request, StreamObserver<GetBatchSkusResponse> responseObserver) {
        java.util.List<String> skuIds = request.getSkuIdsList();
        log.debug("gRPC GetBatchSkus: count={}", skuIds.size());

        try {
            if (skuIds == null || skuIds.isEmpty()) {
                responseObserver.onNext(GetBatchSkusResponse.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }

            Map<String, SkuCacheEntry> entries = productSkuReadService.getByIds(skuIds);

            GetBatchSkusResponse.Builder responseBuilder = GetBatchSkusResponse.newBuilder();

            for (String skuId : skuIds) {
                SkuCacheEntry entry = entries.get(skuId);
                if (entry == null) {
                    responseBuilder.addNotFound(skuId);
                } else if (!entry.isAvailable() || entry.isDeleted()) {
                    responseBuilder.addUnavailable(skuId);
                } else {
                    responseBuilder.putSkus(skuId, toSkuData(entry));
                }
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("gRPC GetBatchSkus error: count={}", skuIds.size(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error while fetching batch SKUs: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }

    private SkuData toSkuData(SkuCacheEntry entry) {
        SkuData.Builder builder = SkuData.newBuilder()
                .setSkuId(entry.getSkuId() != null ? entry.getSkuId() : "")
                .setProductId(entry.getProductId() != null ? entry.getProductId() : "")
                .setProductName(entry.getProductName() != null ? entry.getProductName() : "")
                .setVariantInfo(entry.getVariantAttributes() != null ? entry.getVariantAttributes().toString() : "")
                .setImageUrl(entry.getMainImageUrl() != null ? entry.getMainImageUrl() : "")
                .setPrice(entry.getBestPrice() != null ? entry.getBestPrice().doubleValue() : 0.0)
                .setStatus(entry.getSkuStatus() != null ? entry.getSkuStatus() : "ACTIVE")
                .setStockQty(entry.getDisplayQuantity());

        return builder.build();
    }
}
