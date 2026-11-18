package com.platform.productservice.infrastructure.startup;

import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.GetSkuRequest;
import com.ecommerce.contracts.product.v1.GetSkuResponse;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.ecommerce.contracts.product.v1.SkuNotFound;
import com.platform.productservice.config.ProductProperties;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import com.platform.productservice.infrastructure.grpc.ProductRpcTiming;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationContext;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real loopback transports: a bean-only call would miss the original cold transport path. */
class CatalogWarmupTest {
    private final SimpleMeterRegistry timingMeters = new SimpleMeterRegistry();
    private final ProductProperties properties = new ProductProperties();
    private final SkuRepository skus = mock(SkuRepository.class);
    private final AtomicInteger singles = new AtomicInteger();
    private final AtomicInteger batches = new AtomicInteger();
    private final AtomicInteger httpCalls = new AtomicInteger();
    private final AtomicReference<String> requestedSku = new AtomicReference<>();
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> identity = new AtomicReference<>();
    private final CountDownLatch firstCall = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean block;
    private volatile int httpStatus = 200;
    private HttpServer http;
    private Server grpc;
    private CatalogWarmup warmup;

    @BeforeEach
    void startTransports() throws Exception {
        when(skus.findWarmupTarget()).thenReturn(Optional.empty());
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/internal/v1/product-details/", exchange -> {
            httpCalls.incrementAndGet();
            requestedPath.set(exchange.getRequestURI().getPath());
            identity.set(exchange.getRequestHeaders().getFirst(properties.getInternal().getIdentityHeader()));
            exchange.sendResponseHeaders(httpStatus, -1);
            exchange.close();
        });
        http.start();
        grpc = ServerBuilder.forPort(0).intercept(new ProductRpcTiming(timingMeters))
                .addService(new ProductServiceGrpc.ProductServiceImplBase() {
            @Override
            public void getSku(GetSkuRequest request, StreamObserver<GetSkuResponse> observer) {
                singles.incrementAndGet();
                requestedSku.set(request.getSkuId());
                firstCall.countDown();
                if (block) {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                observer.onNext(GetSkuResponse.newBuilder().setNotFound(
                        SkuNotFound.newBuilder().setSkuId(request.getSkuId())).build());
                observer.onCompleted();
            }

            @Override
            public void getBatchSkus(GetBatchSkusRequest request, StreamObserver<GetBatchSkusResponse> observer) {
                batches.incrementAndGet();
                assertThat(request.getSkuIdsList()).containsExactly(requestedSku.get());
                observer.onNext(GetBatchSkusResponse.getDefaultInstance());
                observer.onCompleted();
            }
        }).build().start();
        warmup = new CatalogWarmup(skus, properties, mock(ApplicationContext.class));
    }

    @AfterEach
    void stopTransports() throws Exception {
        release.countDown();
        grpc.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        http.stop(0);
    }

    @Test
    void readinessWaitsForRealRpcAndBuyerHttpCompletion() throws Exception {
        var target = mock(SkuRepository.WarmupTarget.class);
        UUID skuId = UUID.randomUUID();
        when(target.getSkuId()).thenReturn(skuId);
        when(target.getSlug()).thenReturn("published-product");
        when(skus.findWarmupTarget()).thenReturn(Optional.of(target));
        block = true;
        var execution = CompletableFuture.runAsync(() -> {
            try {
                warmup.warm(http.getAddress().getPort(), grpc.getPort());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        try {
            assertThat(firstCall.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(warmup.health().getStatus()).isEqualTo(Status.DOWN);
            release.countDown();
            execution.get(15, TimeUnit.SECONDS);
            assertThat(warmup.health().getStatus()).isEqualTo(Status.UP);
            assertThat(singles.get()).isEqualTo(3);
            assertThat(batches.get()).isEqualTo(3);
            assertThat(httpCalls.get()).isEqualTo(3);
            assertThat(requestedSku.get()).isEqualTo(skuId.toString());
            assertThat(requestedPath.get()).isEqualTo("/internal/v1/product-details/published-product");
            assertThat(identity.get()).isEqualTo("product-detail-bff");
        } finally {
            release.countDown();
        }
    }

    @Test
    void emptyCatalogMayReturn404WithoutRequiringSeedData() throws Exception {
        httpStatus = 404;
        warmup.warm(http.getAddress().getPort(), grpc.getPort());
        assertThat(warmup.health().getStatus()).isEqualTo(Status.UP);
        assertThat(requestedSku.get()).isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(httpCalls.get()).isEqualTo(3);
        assertThat(timingMeters.get("product.grpc.server.handling").tags("method", "GetBatchSkus", "status", "OK")
                .timer().count()).isEqualTo(3);
    }

    @Test
    void buyerServerErrorsPreventReadiness() {
        httpStatus = 503;
        assertThatThrownBy(() -> warmup.warm(http.getAddress().getPort(), grpc.getPort()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing traffic");
        assertThat(warmup.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void stalledTransportHasBoundedStartupAndCannotBecomeReady() {
        block = true;
        properties.getWarmup().setTimeout(Duration.ofMillis(200));
        long started = System.nanoTime();
        assertThatThrownBy(() -> warmup.warm(http.getAddress().getPort(), grpc.getPort()))
                .isInstanceOfAny(TimeoutException.class, IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(warmup.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void invalidConfigurationCannotDisableTheReadinessGate() {
        properties.getWarmup().setRounds(0);
        assertThatThrownBy(() -> warmup.warm(http.getAddress().getPort(), grpc.getPort()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(warmup.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
