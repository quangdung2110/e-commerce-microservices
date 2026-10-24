package com.platform.productservice.infrastructure.startup;

import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetSkuRequest;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.platform.productservice.config.ProductProperties;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.server.event.GrpcServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Prime the actual transports, serializers and ORM before Spring publishes ACCEPTING_TRAFFIC.
 * The health indicator also prevents the aggregate health endpoint from reporting UP early.
 * This is a bounded, read-only startup operation, not a cache or periodic worker.
 */
@Component("catalogWarmup")
public class CatalogWarmup implements ApplicationRunner, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(CatalogWarmup.class);
    private static final String ABSENT_SKU = "00000000-0000-0000-0000-000000000000";
    private final SkuRepository skus;
    private final ProductProperties properties;
    private final ApplicationContext context;
    private volatile int grpcPort = -1;
    private volatile boolean ready;

    public CatalogWarmup(SkuRepository skus, ProductProperties properties, ApplicationContext context) {
        this.skus = skus;
        this.properties = properties;
        this.context = context;
    }

    @EventListener
    public void grpcStarted(GrpcServerStartedEvent event) {
        grpcPort = event.getServer().getPort();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Mock/non-network application contexts have no HTTP transport to prime.
        if (!(context instanceof WebServerApplicationContext web) || web.getWebServer() == null) {
            ready = true;
            return;
        }
        warm(web.getWebServer().getPort(), grpcPort);
    }

    void warm(int httpPort, int rpcPort) throws Exception {
        ready = false;
        Duration timeout = properties.getWarmup().getTimeout();
        int rounds = properties.getWarmup().getRounds();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || rounds < 1 || rounds > 10) {
            throw new IllegalArgumentException("Catalog warm-up requires a positive timeout and 1..10 rounds");
        }
        if (httpPort <= 0 || rpcPort <= 0) {
            throw new IllegalStateException("Catalog warm-up requires running HTTP and gRPC servers");
        }
        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "catalog-warmup");
            thread.setDaemon(true);
            return thread;
        });
        var work = executor.submit(() -> {
            prime(httpPort, rpcPort, rounds, deadline);
            return null;
        });
        try {
            work.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            ready = true;
            log.info("Catalog warm-up completed in {} ms; HTTP and gRPC ready",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Catalog warm-up failed; refusing traffic", exception.getCause());
        } finally {
            work.cancel(true);
            executor.shutdownNow();
        }
    }

    private void prime(int httpPort, int rpcPort, int rounds, long deadline) throws Exception {
        var target = skus.findWarmupTarget();
        String skuId = target.map(sample -> sample.getSkuId().toString()).orElse(ABSENT_SKU);
        String slug = target.map(SkuRepository.WarmupTarget::getSlug).orElse("catalog-warmup-" + ABSENT_SKU);
        String identity = properties.getInternal().getBuyerReadClients().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Buyer-read warm-up needs a configured allowed caller"));
        // The gRPC allowlist (contracts ADR-004) applies to this loopback call too, so it names an allowed caller.
        Metadata grpcIdentity = new Metadata();
        grpcIdentity.put(Metadata.Key.of("x-service-identity", Metadata.ASCII_STRING_MARSHALLER),
                properties.getInternal().getGrpcClients().stream().findFirst().orElseThrow(
                        () -> new IllegalStateException("gRPC warm-up needs a configured allowed caller")));
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", rpcPort).usePlaintext().build();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int round = 0; round < rounds; round++) {
                var stub = ProductServiceGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(grpcIdentity))
                        .withDeadlineAfter(remaining(deadline), TimeUnit.NANOSECONDS);
                stub.getSku(GetSkuRequest.newBuilder().setSkuId(skuId).build());
                stub.withDeadlineAfter(remaining(deadline), TimeUnit.NANOSECONDS)
                        .getBatchSkus(GetBatchSkusRequest.newBuilder().addSkuIds(skuId).build());
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + httpPort + "/internal/v1/product-details/" + slug))
                        .header(properties.getInternal().getIdentityHeader(), identity)
                        .timeout(Duration.ofNanos(remaining(deadline))).GET().build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                // Empty catalogs and concurrent unpublishing/deletion are legitimate, read-only outcomes.
                if (status != 200 && status != 404) {
                    throw new IllegalStateException("Buyer-read warm-up returned HTTP " + status);
                }
            }
        } finally {
            channel.shutdownNow();
        }
    }

    private static long remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IllegalStateException("Catalog warm-up deadline exceeded");
        }
        return remaining;
    }

    @Override
    public Health health() {
        return ready ? Health.up().build() : Health.down().withDetail("reason", "catalog warm-up incomplete").build();
    }
}
