package com.ecommerce.checkout.client;

import com.ecommerce.checkout.config.CheckoutProperties;
import com.ecommerce.checkout.resilience.ContractViolationException;
import com.ecommerce.checkout.resilience.DependencyGuard;
import com.ecommerce.checkout.resilience.UpstreamStatusException;
import com.ecommerce.contracts.common.v1.Money;
import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.ecommerce.contracts.product.v1.Sku;
import com.ecommerce.contracts.product.v1.SkuStatus;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Product {@code ProductService/GetBatchSkus} over gRPC with a non-blocking future stub
 * (ADR-013). One call covers the whole cart: a cart has at most 50 lines and Product accepts 100
 * unique IDs. The deadline of the call is set by {@code DependencyGuard} through cancellation;
 * the stub deadline is only a ceiling so a hung call cannot outlive its dependency timeout.
 */
@Component
public class ProductClient implements DisposableBean {

    /** {@code ErrorInfo.domain} of the errors Product itself returns (contracts ADR-006). */
    private static final String PRODUCT_ERROR_DOMAIN = "ecommerce.product.v1";
    private static final Metadata.Key<String> SERVICE_IDENTITY =
            Metadata.Key.of("x-service-identity", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> REQUEST_ID =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final ProductServiceGrpc.ProductServiceFutureStub stub;
    private final Duration ceiling;

    @Autowired
    public ProductClient(CheckoutProperties properties) {
        this(channelFor(properties.endpoints().productGrpcTarget()), properties.timeouts().product());
    }

    /** Pings an active connection after this long without traffic (contracts ADR-007). Product permits one every 20 s. */
    static final Duration KEEP_ALIVE_TIME = Duration.ofSeconds(30);
    static final Duration KEEP_ALIVE_TIMEOUT = Duration.ofSeconds(10);
    static final String LOAD_BALANCING_POLICY = "round_robin";

    /**
     * The channel to Product (contracts ADR-007): calls are spread over every address the name resolves to, which only
     * matters without a waypoint (a headless Service lists every pod, a ClusterIP Service lists one); a silent dead
     * connection is noticed by keepalive instead of by the next RPC's deadline; and Product's own
     * {@code max-connection-age} makes the channel reconnect and resolve the name again, so new pods receive calls.
     */
    static ManagedChannel channelFor(String target) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .defaultLoadBalancingPolicy(LOAD_BALANCING_POLICY)
                .keepAliveTime(KEEP_ALIVE_TIME.toSeconds(), TimeUnit.SECONDS)
                .keepAliveTimeout(KEEP_ALIVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .keepAliveWithoutCalls(false)
                .build();
    }

    ProductClient(ManagedChannel channel, Duration ceiling) {
        this.channel = channel;
        this.stub = ProductServiceGrpc.newFutureStub(channel);
        this.ceiling = ceiling;
        // Connect now: the first request after startup must not pay for name resolution and the handshake.
        channel.getState(true);
    }

    /**
     * One catalog SKU as the quotation needs it. {@code imageStorageKey} is Product's object storage
     * key (never a URL); {@code listPrice} is Product's {@code base_price}.
     */
    public record ProductSku(String skuId, String productId, String productName, Map<String, String> variantAttributes,
                             String imageStorageKey, long price, long listPrice, long catalogRevision,
                             List<String> categoryIds) {
    }

    /** Active SKUs by ID, and the requested IDs Product reports as missing or not saleable. */
    public record Batch(Map<String, ProductSku> active, Set<String> notFound, Set<String> unavailable) {

        /** SKUs the buyer cannot check out: missing, inactive or deleted. */
        public Set<String> unsaleable() {
            Set<String> all = new LinkedHashSet<>(notFound);
            all.addAll(unavailable);
            return all;
        }
    }

    public Mono<Batch> fetch(Collection<String> skuIds, RequestContext context) {
        List<String> requested = skuIds.stream().distinct().toList();
        GetBatchSkusRequest request = GetBatchSkusRequest.newBuilder().addAllSkuIds(requested).build();
        return Mono.<GetBatchSkusResponse>create(sink -> {
            Metadata headers = new Metadata();
            headers.put(SERVICE_IDENTITY, UpstreamHttp.SERVICE_IDENTITY);
            headers.put(REQUEST_ID, context.requestId());
            if (context.traceparent() != null) {
                headers.put(TRACEPARENT, context.traceparent());
            }
            // contracts ADR-003: the gRPC deadline is the timeout the guard runs this call under, never more
            // than the ceiling; Product then sees the real deadline instead of a fixed one.
            Duration effective = sink.contextView().getOrDefault(DependencyGuard.EFFECTIVE_TIMEOUT_KEY, ceiling);
            Duration deadline = effective.compareTo(ceiling) < 0 ? effective : ceiling;
            ListenableFuture<GetBatchSkusResponse> future = stub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(Math.max(1, deadline.toMillis()), TimeUnit.MILLISECONDS)
                    .getBatchSkus(request);
            sink.onCancel(() -> future.cancel(true));
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(GetBatchSkusResponse response) {
                    sink.success(response);
                }

                @Override
                public void onFailure(Throwable error) {
                    sink.error(translate(error));
                }
            }, MoreExecutors.directExecutor());
        }).map(response -> toBatch(response, requested));
    }

    /** gRPC statuses classified like their HTTP counterparts so DependencyGuard needs one rule set. */
    static Throwable translate(Throwable error) {
        if (!(error instanceof StatusRuntimeException grpc)) {
            return error;
        }
        Status.Code code = grpc.getStatus().getCode();
        String reason = reasonOf(grpc);
        return switch (code) {
            case DEADLINE_EXCEEDED -> new TimeoutException("Product GetBatchSkus deadline exceeded");
            case UNAVAILABLE -> new IOException("Product unavailable" + (reason == null ? "" : " (" + reason + ")"));
            case INVALID_ARGUMENT -> new UpstreamStatusException(400, reason);
            case UNAUTHENTICATED -> new UpstreamStatusException(401, reason);
            case PERMISSION_DENIED -> new UpstreamStatusException(403, reason);
            default -> new UpstreamStatusException(503, reason);
        };
    }

    /**
     * The stable {@code ErrorInfo.reason} Product attached to a failed call (contracts ADR-006), or null when the
     * status carries none: the gRPC runtime and the mesh send statuses without details. Callers log it so that
     * "identity not allowed" is told apart from "batch too large" without parsing the free-text description.
     */
    static String reasonOf(StatusRuntimeException error) {
        com.google.rpc.Status details = StatusProto.fromThrowable(error);
        if (details == null) {
            return null;
        }
        for (Any detail : details.getDetailsList()) {
            if (detail.is(ErrorInfo.class)) {
                try {
                    ErrorInfo info = detail.unpack(ErrorInfo.class);
                    return PRODUCT_ERROR_DOMAIN.equals(info.getDomain()) ? info.getReason() : null;
                } catch (InvalidProtocolBufferException malformed) {
                    return null;
                }
            }
        }
        return null;
    }

    static Batch toBatch(GetBatchSkusResponse response, List<String> requested) {
        Map<String, ProductSku> active = new LinkedHashMap<>();
        Set<String> notFound = new LinkedHashSet<>(response.getNotFoundSkuIdsList());
        Set<String> unavailable = new LinkedHashSet<>(response.getUnavailableSkuIdsList());
        for (String id : requested) {
            Sku sku = response.getSkusMap().get(id);
            if (sku == null) {
                if (!notFound.contains(id) && !unavailable.contains(id)) {
                    throw new ContractViolationException("GetBatchSkus: SKU absent from every result list");
                }
                continue;
            }
            if (!id.equals(sku.getSkuId())) {
                throw new ContractViolationException("GetBatchSkus: map key differs from sku_id");
            }
            if (sku.getStatus() != SkuStatus.SKU_STATUS_ACTIVE) {
                unavailable.add(id);
                continue;
            }
            active.put(id, productSku(sku));
        }
        return new Batch(active, notFound, unavailable);
    }

    private static ProductSku productSku(Sku sku) {
        if (!isUuid(sku.getProductId())) {
            throw new ContractViolationException("GetBatchSkus: product_id is not a UUID");
        }
        if (sku.getProductName().isBlank() || sku.getProductName().length() > 255) {
            throw new ContractViolationException("GetBatchSkus: product_name missing or too long");
        }
        if (!sku.hasPrice() || !sku.hasBasePrice()) {
            throw new ContractViolationException("GetBatchSkus: price or base_price missing");
        }
        long price = vnd(sku.getPrice());
        long basePrice = vnd(sku.getBasePrice());
        if (price > basePrice) {
            throw new ContractViolationException("GetBatchSkus: price exceeds base_price");
        }
        if (sku.getCategoryIdsCount() > 20) {
            throw new ContractViolationException("GetBatchSkus: more than 20 category_ids");
        }
        Set<String> categories = new HashSet<>();
        for (String category : sku.getCategoryIdsList()) {
            if (category.isEmpty() || category.length() > 64 || !categories.add(category)) {
                throw new ContractViolationException("GetBatchSkus: invalid or duplicate category_id");
            }
        }
        if (sku.getVariantAttributesCount() > 50
                || sku.getVariantAttributesMap().values().stream().anyMatch(value -> value.length() > 255)) {
            throw new ContractViolationException("GetBatchSkus: variant_attributes out of range");
        }
        return new ProductSku(sku.getSkuId(), sku.getProductId(), sku.getProductName(),
                Map.copyOf(sku.getVariantAttributesMap()), sku.getImageUrl(), price, basePrice,
                sku.getCatalogRevision(), List.copyOf(sku.getCategoryIdsList()));
    }

    private static long vnd(Money money) {
        if (!"VND".equals(money.getCurrency()) || money.getAmountMinor() < 0) {
            throw new ContractViolationException("GetBatchSkus: money is not a non-negative VND amount");
        }
        return money.getAmountMinor();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
