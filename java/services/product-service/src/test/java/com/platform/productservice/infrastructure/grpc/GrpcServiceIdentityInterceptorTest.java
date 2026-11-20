package com.platform.productservice.infrastructure.grpc;

import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetBatchSkusResponse;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.platform.productservice.config.ProductProperties;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** contracts ADR-004 for gRPC: the caller names itself in x-service-identity and must be on the allowlist. */
class GrpcServiceIdentityInterceptorTest {

    private static final Metadata.Key<String> IDENTITY =
            Metadata.Key.of("x-service-identity", Metadata.ASCII_STRING_MARSHALLER);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        ProductProperties properties = new ProductProperties();
        properties.getInternal().setGrpcClients(List.of("cart-service", "checkout-service"));
        server = ServerBuilder.forPort(0)
                .intercept(new GrpcServiceIdentityInterceptor(properties, meters))
                .addService(new ProductServiceGrpc.ProductServiceImplBase() {
                    @Override
                    public void getBatchSkus(GetBatchSkusRequest request, StreamObserver<GetBatchSkusResponse> out) {
                        out.onNext(GetBatchSkusResponse.getDefaultInstance());
                        out.onCompleted();
                    }
                })
                .build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    private ProductServiceGrpc.ProductServiceBlockingStub stub(String identity) {
        ProductServiceGrpc.ProductServiceBlockingStub stub = ProductServiceGrpc.newBlockingStub(channel);
        if (identity == null) {
            return stub;
        }
        Metadata metadata = new Metadata();
        metadata.put(IDENTITY, identity);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static final GetBatchSkusRequest REQUEST = GetBatchSkusRequest.newBuilder().addSkuIds("sku").build();

    @Test
    void anAllowedCallerReachesTheService() {
        assertThat(stub("cart-service").getBatchSkus(REQUEST)).isNotNull();
        assertThat(stub("checkout-service").getBatchSkus(REQUEST)).isNotNull();
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void aMissingIdentityIsUnauthenticated() {
        assertThatThrownBy(() -> stub(null).getBatchSkus(REQUEST))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(meters.counter("product.grpc.server.identity_rejected", "reason", "missing").count()).isEqualTo(1.0);
    }

    @Test
    void aBlankIdentityIsUnauthenticatedToo() {
        assertThatThrownBy(() -> stub("   ").getBatchSkus(REQUEST))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void aCallerOutsideTheAllowlistIsPermissionDenied() {
        assertThatThrownBy(() -> stub("search-service").getBatchSkus(REQUEST))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThat(meters.counter("product.grpc.server.identity_rejected", "reason", "forbidden").count()).isEqualTo(1.0);
    }

    @Test
    void anEmptyAllowlistRejectsEveryCaller() {
        ProductProperties empty = new ProductProperties();
        empty.getInternal().setGrpcClients(List.of());
        GrpcServiceIdentityInterceptor interceptor = new GrpcServiceIdentityInterceptor(empty, meters);
        Server closed = null;
        try {
            closed = ServerBuilder.forPort(0).intercept(interceptor)
                    .addService(new ProductServiceGrpc.ProductServiceImplBase() {
                    }).build().start();
            ManagedChannel other = ManagedChannelBuilder.forAddress("127.0.0.1", closed.getPort()).usePlaintext().build();
            Metadata metadata = new Metadata();
            metadata.put(IDENTITY, "cart-service");
            var denied = ProductServiceGrpc.newBlockingStub(other)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
            assertThatThrownBy(() -> denied.getBatchSkus(REQUEST))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            other.shutdownNow();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (closed != null) {
                closed.shutdownNow();
            }
        }
    }
}
