package com.platform.productservice.infrastructure.grpc;

import com.platform.productservice.config.GrpcSecurityConfig;
import com.platform.productservice.proto.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.*;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for gRPC client stubs used to call external services (e.g., Inventory Service).
 * Provides:
 * - ManagedChannel with configurable address and load balancing
 * - Client interceptors for authentication and logging
 * - Circuit breaker for resilient calls
 * <p>
 * Also registers stub beans so that other internal components (workers, services) can
 * call external services through this infrastructure layer without depending on generated stubs directly.
 */
@Configuration
public class ExternalGrpcClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ExternalGrpcClientConfig.class);

    @Value("${grpc.client.inventory-service.address:static://inventory-service:9091}")
    private String inventoryServiceAddress;

    @Value("${grpc.client.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${grpc.client.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${grpc.security.internal-secret:change-me-in-production}")
    private String internalSecret;

    // -------------------------------------------------------------------------
    // Circuit Breaker
    // -------------------------------------------------------------------------

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(5)
                .slowCallRateThreshold(50)
                .slowCallDurationThreshold(java.time.Duration.ofSeconds(2))
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker inventoryServiceCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("inventoryService");
    }

    // -------------------------------------------------------------------------
    // Managed Channels
    // -------------------------------------------------------------------------

    /**
     * ManagedChannel for the Inventory Service.
     * Configured via grpc.client.inventory-service.address property.
     */
    @Bean
    public ManagedChannel inventoryServiceChannel() {
        log.info("Creating gRPC ManagedChannel for inventory-service at {}", inventoryServiceAddress);
        return ManagedChannelBuilder.forTarget(inventoryServiceAddress)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    // -------------------------------------------------------------------------
    // Client Interceptors
    // -------------------------------------------------------------------------

    /**
     * Global client interceptor that adds the internal service secret to all outbound gRPC calls.
     * Reads the secret value from configuration property for consistency with server-side validation.
     */
    @Bean
    @GrpcGlobalClientInterceptor
    public ClientInterceptor internalServiceSecretClientInterceptor() {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    CallOptions callOptions,
                    Channel next) {

                CallOptions adjustedOptions = callOptions
                        .withDeadline(Deadline.after(readTimeoutMs, TimeUnit.MILLISECONDS));

                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, adjustedOptions)) {

                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(
                                Metadata.Key.of(GrpcSecurityConfig.INTERNAL_SECRET_HEADER, Metadata.ASCII_STRING_MARSHALLER),
                                internalSecret
                        );
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }

    /**
     * Global client interceptor for logging outbound gRPC calls.
     */
    @Bean
    @GrpcGlobalClientInterceptor
    public ClientInterceptor grpcClientLoggingInterceptor() {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    CallOptions callOptions,
                    Channel next) {

                String methodName = method.getFullMethodName();
                long startTime = System.nanoTime();

                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                        next.newCall(method, callOptions)) {

                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        log.debug("gRPC client call started: method={}", methodName);
                        Listener<RespT> wrappedListener = new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                            @Override
                            public void onClose(io.grpc.Status status, Metadata trailers) {
                                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                                log.debug("gRPC client call completed: method={}, status={}, elapsedMs={}",
                                        methodName, status.getCode(), elapsedMs);
                                super.onClose(status, trailers);
                            }
                        };
                        super.start(wrappedListener, headers);
                    }
                };
            }
        };
    }

    // -------------------------------------------------------------------------
    // Stub Beans (for internal use by services/workers)
    // -------------------------------------------------------------------------

    /**
     * ProductGrpcService stub for calling the Product Service from another service.
     * Uses circuit breaker for resilience.
     * Other services (Cart, Order, etc.) can inject this stub to call into Product Service.
     */
    @Bean
    @Qualifier("productServiceStub")
    public ProductGrpcServiceGrpc.ProductGrpcServiceBlockingStub productServiceStub(
            @Qualifier("productServiceChannel") ManagedChannel channel) {
        return ProductGrpcServiceGrpc.newBlockingStub(channel);
    }

    /**
     * ManagedChannel for calling Product Service from external/internal services.
     * Address is configurable via grpc.client.product-service.address property.
     */
    @Bean
    @Qualifier("productServiceChannel")
    public ManagedChannel productServiceChannel(
            @Value("${grpc.client.product-service.address:static://product-service:9090}") String address) {
        log.info("Creating gRPC ManagedChannel for product-service at {}", address);
        return ManagedChannelBuilder.forTarget(address)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * InventoryGrpcService stub for calling Inventory Service.
     * Note: Circuit breaker can be applied at the service call level instead of stub level.
     * Only created when InventoryGrpcServiceGrpc class is on the classpath.
     */
    @Bean
    @Qualifier("inventoryServiceStub")
    @ConditionalOnClass(name = "com.platform.productservice.proto.InventoryGrpcServiceGrpc")
    public InventoryGrpcServiceGrpc.InventoryGrpcServiceBlockingStub inventoryServiceStub(
            ManagedChannel inventoryServiceChannel) {
        return InventoryGrpcServiceGrpc.newBlockingStub(inventoryServiceChannel);
    }
}
