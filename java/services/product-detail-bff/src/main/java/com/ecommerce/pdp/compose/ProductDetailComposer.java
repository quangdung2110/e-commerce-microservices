package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.api.ProductDetailResponse;
import com.ecommerce.pdp.api.ProductDetailResponse.AvailabilityStatus;
import com.ecommerce.pdp.client.InventoryClient;
import com.ecommerce.pdp.client.InventoryPayload;
import com.ecommerce.pdp.client.ProductClient;
import com.ecommerce.pdp.client.ProductPayload;
import com.ecommerce.pdp.client.RequestContext;
import com.ecommerce.pdp.client.ReviewClient;
import com.ecommerce.pdp.client.ReviewPayload;
import com.ecommerce.pdp.resilience.CallResult;
import com.ecommerce.pdp.resilience.Dependency;
import com.ecommerce.pdp.resilience.DependencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * WF-PDP-001 (docs/product-detail-bff/workflows.md): Product trước, rồi Inventory và
 * Review song song, rồi compose. Product là dependency bắt buộc; Inventory và Review chỉ
 * degrade phần của mình thành {@code UNKNOWN}/{@code UNAVAILABLE}.
 *
 * <p>Không có retry, cache hay trạng thái nào giữa các request. Mọi quyết định degrade
 * dựa trên {@link CallResult} do {@link DependencyGuard} phân loại.
 */
@Component
public class ProductDetailComposer {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailComposer.class);

    private final DependencyGuard guard;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final ReviewClient reviewClient;
    private final MediaUrls mediaUrls;
    private final PdpMetrics metrics;

    public ProductDetailComposer(DependencyGuard guard,
                                 ProductClient productClient,
                                 InventoryClient inventoryClient,
                                 ReviewClient reviewClient,
                                 MediaUrls mediaUrls,
                                 PdpMetrics metrics) {
        this.guard = guard;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.reviewClient = reviewClient;
        this.mediaUrls = mediaUrls;
        this.metrics = metrics;
    }

    public Mono<PdpResult> compose(String slug, RequestContext context) {
        return guard.call(Dependency.PRODUCT, context.deadline(), () -> productClient.fetch(slug, context))
                .flatMap(product -> {
                    if (!product.isSuccess()) {
                        return Mono.just(new PdpResult.Unavailable(product.retryAfterSeconds()));
                    }
                    Optional<ProductPayload> found = product.value();
                    if (found.isEmpty()) {
                        return Mono.just(new PdpResult.NotFound());
                    }
                    return enrich(found.get(), context);
                });
    }

    /** Pha 2: một Inventory batch và một Review read, song song, mỗi cái tự degrade. */
    private Mono<PdpResult> enrich(ProductPayload product, RequestContext context) {
        List<String> skuIds = product.skus().stream().map(ProductPayload.Sku::skuId).toList();
        Mono<CallResult<InventoryPayload>> availability = guard.call(Dependency.INVENTORY, context.deadline(),
                () -> inventoryClient.batchGet(skuIds, context));
        Mono<CallResult<ReviewPayload>> review = guard.call(Dependency.REVIEW, context.deadline(),
                () -> reviewClient.pdpSummary(product.productId(), context));
        return Mono.zip(availability, review)
                .map(results -> new PdpResult.Found(
                        assemble(product, availabilityBySku(skuIds, results.getT1(), context),
                                reviewSection(results.getT2()))));
    }

    private record Availability(AvailabilityStatus status, OffsetDateTime observedAt) {

        static final Availability UNKNOWN = new Availability(AvailabilityStatus.UNKNOWN, null);
    }

    /**
     * Chỉ Inventory được nói {@code IN_STOCK}/{@code OUT_OF_STOCK} (PDP-BR-002). Item thiếu
     * thành {@code UNKNOWN}, item cho SKU không yêu cầu bị bỏ; cả hai ghi vi phạm mức item.
     * Lời gọi không cho kết quả thì mọi SKU là {@code UNKNOWN} (PDP-BR-003).
     */
    private Map<String, Availability> availabilityBySku(List<String> skuIds, CallResult<InventoryPayload> result,
                                                        RequestContext context) {
        Map<String, Availability> bySku = new HashMap<>();
        if (!result.isSuccess()) {
            skuIds.forEach(skuId -> bySku.put(skuId, Availability.UNKNOWN));
            metrics.unknownSkus(PdpMetrics.unknownSource(result.outcome()), skuIds.size());
            return bySku;
        }
        Map<String, InventoryPayload.Item> items = result.value().items().stream()
                .collect(Collectors.toMap(InventoryPayload.Item::skuId, Function.identity()));
        Set<String> requested = Set.copyOf(skuIds);
        int missing = 0;
        int returnedUnknown = 0;
        for (String skuId : skuIds) {
            InventoryPayload.Item item = items.get(skuId);
            if (item == null) {
                missing++;
                bySku.put(skuId, Availability.UNKNOWN);
                continue;
            }
            AvailabilityStatus status = AvailabilityStatus.valueOf(item.status());
            if (status == AvailabilityStatus.UNKNOWN) {
                returnedUnknown++;
                bySku.put(skuId, Availability.UNKNOWN);
            } else {
                bySku.put(skuId, new Availability(status, item.evaluatedAt()));
            }
        }
        int extra = (int) items.keySet().stream().filter(skuId -> !requested.contains(skuId)).count();
        metrics.unknownSkus(PdpMetrics.UnknownSource.INVENTORY_RETURNED, returnedUnknown);
        metrics.unknownSkus(PdpMetrics.UnknownSource.INVENTORY_MISSING_ITEM, missing);
        metrics.inventoryItemViolations("missing", missing);
        metrics.inventoryItemViolations("extra", extra);
        if (missing > 0 || extra > 0) {
            log.warn("inventory broke the one-item-per-SKU promise: requested={} missing={} extra={} {} {}",
                    skuIds.size(), missing, extra,
                    kv("request_id", context.requestId()), kv("trace_id", context.traceId()));
        }
        return bySku;
    }

    /** Review lỗi không bao giờ thành aggregate 0 (PDP-BR-007). */
    private ProductDetailResponse.ReviewSection reviewSection(CallResult<ReviewPayload> result) {
        if (!result.isSuccess()) {
            metrics.reviewUnavailable(result.outcome());
            return ProductDetailResponse.ReviewSection.unavailable();
        }
        ReviewPayload review = result.value();
        return new ProductDetailResponse.ReviewSection(
                ProductDetailResponse.ReviewSectionStatus.AVAILABLE,
                new ProductDetailResponse.ReviewSummary(
                        review.aggregate().averageRating(),
                        review.aggregate().totalReviews(),
                        review.aggregate().totalWithMedia()),
                review.preview().stream()
                        .map(item -> new ProductDetailResponse.ReviewPreviewItem(
                                item.reviewId(),
                                item.rating(),
                                item.content(),
                                item.authorName(),
                                item.variantName(),
                                item.hasMedia(),
                                item.createdAt()))
                        .toList());
    }

    /**
     * Catalog chép nguyên thứ tự Product trả (PDP-BR-010); chỉ storage key đổi thành URL
     * (PDP-BR-011). Response Inventory không thể tạo hay lộ SKU: chỉ SKU Product trả mới
     * có mặt.
     */
    private ProductDetailResponse assemble(ProductPayload product,
                                           Map<String, Availability> availability,
                                           ProductDetailResponse.ReviewSection reviewSection) {
        ProductPayload.Category category = product.category();
        return new ProductDetailResponse(
                product.productId(),
                product.name(),
                product.slug(),
                product.description(),
                product.catalogRevision(),
                new ProductDetailResponse.Category(
                        category.categoryId(),
                        category.name(),
                        category.slug(),
                        category.path().stream()
                                .map(segment -> new ProductDetailResponse.CategoryRef(
                                        segment.categoryId(), segment.name(), segment.slug()))
                                .toList()),
                product.attributes().stream()
                        .map(attribute -> new ProductDetailResponse.Attribute(
                                attribute.code(), attribute.name(), attribute.valueType(),
                                attribute.value(), attribute.valueLabel()))
                        .toList(),
                product.variantAxes().stream()
                        .map(axis -> new ProductDetailResponse.VariantAxis(
                                axis.code(), axis.name(), axis.valueType(),
                                axis.options().stream()
                                        .map(option -> new ProductDetailResponse.VariantOption(
                                                option.value(), option.label()))
                                        .toList()))
                        .toList(),
                product.media().stream()
                        .map(item -> new ProductDetailResponse.Media(
                                item.mediaId(), item.skuId(), item.mediaType(),
                                mediaUrls.url(item.storageKey()), item.altText(), item.sortOrder()))
                        .toList(),
                product.skus().stream()
                        .map(sku -> {
                            Availability state = availability.getOrDefault(sku.skuId(), Availability.UNKNOWN);
                            return new ProductDetailResponse.Sku(
                                    sku.skuId(),
                                    sku.skuCode(),
                                    sku.variantAttributes(),
                                    mediaUrls.thumbnail(sku.thumbnailStorageKey()),
                                    sku.basePriceVnd(),
                                    sku.sellingPriceVnd(),
                                    state.status(),
                                    state.observedAt());
                        })
                        .toList(),
                reviewSection);
    }
}
