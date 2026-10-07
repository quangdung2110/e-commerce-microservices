package com.ecommerce.checkout.support;

import com.ecommerce.contracts.common.v1.Money;
import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.ecommerce.contracts.product.v1.Sku;
import com.ecommerce.contracts.product.v1.SkuStatus;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process Product {@code GetBatchSkus} server built from the canonical proto. Tests configure the
 * catalog, a delay or a gRPC status, and read back the requests and metadata Checkout sent.
 */
public final class FakeProduct {

    private final Map<String, Sku> skus = new LinkedHashMap<>();
    private final Set<String> notFound = new LinkedHashSet<>();
    private final Set<String> unavailable = new LinkedHashSet<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<List<String>> requests = new CopyOnWriteArrayList<>();
    private volatile Metadata lastHeaders = new Metadata();
    private volatile Status failure;
    private volatile long delayMillis;
    private final Server server;

    public FakeProduct() {
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                          Metadata headers,
                                                                          ServerCallHandler<ReqT, RespT> next) {
                lastHeaders = headers;
                return next.startCall(call, headers);
            }
        };
        try {
            this.server = NettyServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(new Impl(), capture))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return server.getPort();
    }

    public void stop() {
        server.shutdownNow();
    }

    public synchronized void reset() {
        skus.clear();
        notFound.clear();
        unavailable.clear();
        requests.clear();
        calls.set(0);
        failure = null;
        delayMillis = 0;
        lastHeaders = new Metadata();
    }

    public synchronized void put(Sku sku) {
        skus.put(sku.getSkuId(), sku);
    }

    public synchronized void markNotFound(String skuId) {
        notFound.add(skuId);
    }

    public synchronized void markUnavailable(String skuId) {
        unavailable.add(skuId);
    }

    public void failWith(Status status) {
        this.failure = status;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public int calls() {
        return calls.get();
    }

    public List<List<String>> requests() {
        return new ArrayList<>(requests);
    }

    public String header(String name) {
        return lastHeaders.get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
    }

    /** A catalog SKU as the Product service returns it. */
    public static Sku.Builder sku(String skuId, String productId, String name, long price, long basePrice) {
        return Sku.newBuilder().setSkuId(skuId).setProductId(productId).setProductName(name)
                .setStatus(SkuStatus.SKU_STATUS_ACTIVE)
                .setPrice(Money.newBuilder().setAmountMinor(price).setCurrency("VND"))
                .setBasePrice(Money.newBuilder().setAmountMinor(basePrice).setCurrency("VND"))
                .setCatalogRevision(7);
    }

    private final class Impl extends ProductServiceGrpc.ProductServiceImplBase {
        @Override
        public void getBatchSkus(GetBatchSkusRequest request, StreamObserver<GetBatchSkusResponse> observer) {
            calls.incrementAndGet();
            requests.add(List.copyOf(request.getSkuIdsList()));
            long delay = delayMillis;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Status error = failure;
            if (error != null) {
                observer.onError(error.asRuntimeException());
                return;
            }
            GetBatchSkusResponse.Builder response = GetBatchSkusResponse.newBuilder();
            synchronized (FakeProduct.this) {
                for (String id : request.getSkuIdsList()) {
                    if (skus.containsKey(id)) {
                        response.putSkus(id, skus.get(id));
                    } else if (unavailable.contains(id)) {
                        response.addUnavailableSkuIds(id);
                    } else {
                        response.addNotFoundSkuIds(id);
                    }
                }
            }
            observer.onNext(response.build());
            observer.onCompleted();
        }
    }
}
