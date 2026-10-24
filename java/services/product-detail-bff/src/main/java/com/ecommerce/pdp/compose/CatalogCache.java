package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.client.ProductClient;
import com.ecommerce.pdp.client.ProductPayload;
import com.ecommerce.pdp.client.RequestContext;
import com.ecommerce.pdp.config.CatalogCacheProperties;
import com.ecommerce.pdp.config.PdpProperties;
import com.ecommerce.pdp.resilience.CallResult;
import com.ecommerce.pdp.resilience.Deadline;
import com.ecommerce.pdp.resilience.Dependency;
import com.ecommerce.pdp.resilience.DependencyGuard;
import com.ecommerce.pdp.resilience.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * Buyer-read Product của PDP qua cache in-process (ADR-011): tải lên Product tỉ lệ với số
 * slug khác nhau được xem trong một TTL, không còn tỉ lệ với số lượt xem.
 *
 * <ul>
 *   <li>Chỉ payload {@code 200} đã validate được giữ, tối đa {@code ttl} kể từ lúc Product
 *       trả. {@code 404}, timeout, lỗi, circuit mở, bulkhead đầy không bao giờ được giữ.</li>
 *   <li>Request cùng slug khi chưa có entry chờ chung một lời gọi Product (single-flight).
 *       Lời gọi đó chạy tách khỏi request khởi tạo, dưới budget Product đầy đủ của
 *       {@link DependencyGuard}: client của request đầu ngắt kết nối không làm hỏng các
 *       request đang chờ. Mỗi request chờ theo deadline của chính nó.</li>
 *   <li>Cache tắt thì hành vi y như trước ADR-011: mỗi request một lời gọi Product.</li>
 * </ul>
 *
 * <p>Không phải cache của response PDP: Inventory và Review vẫn được gọi theo từng request,
 * nên quy tắc degrade và {@code Cache-Control} không đổi (data.md §Cache boundary).
 */
@Component
public class CatalogCache {

    private final DependencyGuard guard;
    private final ProductClient productClient;
    private final PdpProperties pdp;
    private final CatalogCacheProperties settings;
    private final Cache<String, ProductPayload> values;
    private final Map<String, CompletableFuture<CallResult<Optional<ProductPayload>>>> loading =
            new ConcurrentHashMap<>();
    private final Counter hits;
    private final Counter misses;
    private final Counter coalesced;
    private final Counter waitTimeouts;

    public CatalogCache(DependencyGuard guard, ProductClient productClient, PdpProperties pdp,
                        CatalogCacheProperties settings, MeterRegistry registry) {
        this.guard = guard;
        this.productClient = productClient;
        this.pdp = pdp;
        this.settings = settings;
        this.values = Caffeine.newBuilder()
                .expireAfterWrite(settings.ttl())
                .maximumWeight(settings.maxWeight().toBytes())
                .weigher((String slug, ProductPayload payload) -> estimatedBytes(payload))
                .build();
        this.hits = requests(registry, "hit");
        this.misses = requests(registry, "miss");
        this.coalesced = requests(registry, "coalesced");
        this.waitTimeouts = Counter.builder("pdp.catalog.cache.wait.timeouts")
                .description("Requests whose own deadline ran out while waiting for a shared Product load")
                .register(registry);
        Gauge.builder("pdp.catalog.cache.entries", values, Cache::estimatedSize)
                .description("Catalog entries held by the PDP catalog cache")
                .register(registry);
        Gauge.builder("pdp.catalog.cache.weight", values,
                        cache -> cache.policy().eviction().map(eviction -> eviction.weightedSize().orElse(0))
                                .orElse(0L))
                .description("Estimated bytes held by the PDP catalog cache")
                .baseUnit("bytes")
                .register(registry);
    }

    private static Counter requests(MeterRegistry registry, String result) {
        return Counter.builder("pdp.catalog.cache.requests")
                .description("Product lookups of the PDP by cache result")
                .tag("result", result)
                .register(registry);
    }

    /**
     * Catalog của {@code slug}: {@code Optional.empty()} là {@code 404} của Product; kết quả
     * không thành công mang outcome như lời gọi Product trực tiếp.
     */
    public Mono<CallResult<Optional<ProductPayload>>> lookup(String slug, RequestContext context) {
        return Mono.deferContextual(initiatorContext -> lookup(slug, context, initiatorContext));
    }

    private Mono<CallResult<Optional<ProductPayload>>> lookup(
            String slug, RequestContext context, reactor.util.context.ContextView initiatorContext) {
        if (!settings.enabled()) {
            return fetch(slug, context.deadline(), context);
        }
        ProductPayload cached = values.getIfPresent(slug);
        if (cached != null) {
            hits.increment();
            return Mono.just(CallResult.success(Optional.of(cached)));
        }
        Duration waitBudget = context.deadline().remaining().minus(pdp.composeReserve());
        if (context.deadline().remaining().compareTo(pdp.phaseMinRemaining()) < 0) {
            // Không đủ budget để chờ: guard ghi budget_skipped và không gọi Product (ADR-007).
            return fetch(slug, context.deadline(), context);
        }
        boolean[] started = {false};
        CompletableFuture<CallResult<Optional<ProductPayload>>> shared = loading.computeIfAbsent(slug, key -> {
            started[0] = true;
            return new CompletableFuture<>();
        });
        if (started[0]) {
            misses.increment();
            load(slug, context, shared, initiatorContext);
        } else {
            coalesced.increment();
        }
        // suppressCancel: request này bị hủy thì chỉ nó thôi chờ, lời gọi chung vẫn chạy.
        return Mono.fromFuture(shared, true)
                .timeout(waitBudget)
                .onErrorResume(TimeoutException.class, error -> {
                    waitTimeouts.increment();
                    return Mono.just(CallResult.failure(Outcome.TIMEOUT));
                });
    }

    /**
     * Lời gọi Product chung của một slug. Budget tính từ lúc bắt đầu tải, không từ request
     * khởi tạo, để mọi request đang chờ cùng nhận một kết quả đầy đủ budget Product. Entry
     * thành công vào cache trước khi rời {@code loading}, nên không có khe nào một request
     * mới gọi Product lần nữa cho cùng kết quả.
     */
    private void load(String slug, RequestContext initiator,
                      CompletableFuture<CallResult<Optional<ProductPayload>>> shared,
                      reactor.util.context.ContextView initiatorContext) {
        fetch(slug, Deadline.startingNow(pdp.deadline()), initiator)
                // The detached subscription survives caller cancellation, but its WebClient
                // must still be a child of the initiating request's observation.
                .contextWrite(context -> context
                        .putAll(initiatorContext)
                        .put(DependencyGuard.REQUEST_ID_KEY, initiator.requestId())
                        .put(DependencyGuard.TRACE_ID_KEY, initiator.traceId()))
                .subscribe(result -> {
                    if (result.isSuccess() && result.value().isPresent()) {
                        values.put(slug, result.value().get());
                    }
                    loading.remove(slug, shared);
                    shared.complete(result);
                }, error -> {
                    // DependencyGuard luôn trả CallResult; nhánh này chỉ để không bỏ rơi request đang chờ.
                    loading.remove(slug, shared);
                    shared.complete(CallResult.failure(Outcome.CONNECT_ERROR));
                });
    }

    private Mono<CallResult<Optional<ProductPayload>>> fetch(String slug, Deadline deadline, RequestContext context) {
        return guard.call(Dependency.PRODUCT, deadline, () -> productClient.fetch(slug, context));
    }

    /** Cho test: xóa mọi entry đã giữ. */
    public void invalidateAll() {
        values.invalidateAll();
    }

    /**
     * Ước lượng bộ nhớ của một payload để {@code maxWeight} có nghĩa: 2 byte mỗi ký tự cộng
     * phần đầu của mỗi object/list. Không cần chính xác, chỉ cần tỉ lệ với kích thước thật.
     */
    static int estimatedBytes(ProductPayload payload) {
        long bytes = 256 + chars(payload.productId(), payload.name(), payload.slug(), payload.description());
        ProductPayload.Category category = payload.category();
        bytes += 64 + chars(category.categoryId(), category.name(), category.slug());
        for (ProductPayload.CategoryRef segment : category.path()) {
            bytes += 64 + chars(segment.categoryId(), segment.name(), segment.slug());
        }
        for (ProductPayload.Attribute attribute : payload.attributes()) {
            bytes += 96 + chars(attribute.code(), attribute.name(), attribute.valueType(), attribute.valueLabel())
                    + node(attribute.value());
        }
        for (ProductPayload.VariantAxis axis : payload.variantAxes()) {
            bytes += 96 + chars(axis.code(), axis.name(), axis.valueType());
            for (ProductPayload.VariantOption option : axis.options()) {
                bytes += 64 + chars(option.label()) + node(option.value());
            }
        }
        for (ProductPayload.Media media : payload.media()) {
            bytes += 96 + chars(media.mediaId(), media.skuId(), media.mediaType(), media.storageKey(),
                    media.altText());
        }
        for (ProductPayload.Sku sku : payload.skus()) {
            bytes += 128 + chars(sku.skuId(), sku.skuCode(), sku.thumbnailStorageKey());
            for (Map.Entry<String, JsonNode> entry : sku.variantAttributes().entrySet()) {
                bytes += 64 + chars(entry.getKey()) + node(entry.getValue());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    private static long chars(String... values) {
        long total = 0;
        for (String value : values) {
            total += value == null ? 0 : 40 + 2L * value.length();
        }
        return total;
    }

    private static long node(JsonNode value) {
        return value == null ? 0 : 32 + (value.isTextual() ? 2L * value.textValue().length() : 16);
    }

    /** Cho test: số entry đang giữ, sau khi Caffeine dọn entry hết hạn. */
    long size() {
        values.cleanUp();
        return values.estimatedSize();
    }

    /** Cho test: số slug đang có lời gọi Product chung chưa xong. */
    int inFlight() {
        return loading.size();
    }
}
