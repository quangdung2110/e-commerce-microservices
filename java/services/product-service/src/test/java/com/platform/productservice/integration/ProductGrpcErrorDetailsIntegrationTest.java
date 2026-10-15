package com.platform.productservice.integration;

import com.ecommerce.contracts.product.v1.GetBatchSkusRequest;
import com.ecommerce.contracts.product.v1.GetSkuRequest;
import com.ecommerce.contracts.product.v1.ProductServiceGrpc;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import com.platform.productservice.infrastructure.grpc.ProductGrpcError;
import com.platform.productservice.infrastructure.persistence.SkuRepository;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * contracts ADR-006 over a real gRPC call: interceptor, service and exception advice all put {@code ErrorInfo}
 * in the status details, so a caller reads {@code reason} and {@code retryable} instead of free text.
 */
@TestPropertySource(properties = {
        "grpc.server.in-process-name=product-error-details",
        "product.internal.grpc-clients=cart-service",
        // The connection policy of application.yml (contracts ADR-007) is for the Netty server; the in-process transport used here
        // rejects it at startup, so it is cleared for this test only.
        "grpc.server.max-connection-age=",
        "grpc.server.max-connection-age-grace=",
        "grpc.server.permit-keep-alive-time="
})
class ProductGrpcErrorDetailsIntegrationTest extends AbstractCatalogIntegrationTest {

    private static final Metadata.Key<String> IDENTITY =
            Metadata.Key.of("x-service-identity", Metadata.ASCII_STRING_MARSHALLER);

    @MockBean
    private SkuRepository skus;

    private ManagedChannel channel;

    @BeforeEach
    void connect() {
        channel = InProcessChannelBuilder.forName("product-error-details").directExecutor().build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
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

    private static ErrorInfo errorInfo(StatusRuntimeException e) throws InvalidProtocolBufferException {
        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        assertThat(status).as("status details are present").isNotNull();
        for (com.google.protobuf.Any detail : status.getDetailsList()) {
            if (detail.is(ErrorInfo.class)) {
                return detail.unpack(ErrorInfo.class);
            }
        }
        throw new AssertionError("no ErrorInfo in " + status);
    }

    private static <T extends com.google.protobuf.Message> T detail(StatusRuntimeException e, Class<T> type)
            throws InvalidProtocolBufferException {
        for (com.google.protobuf.Any detail : StatusProto.fromThrowable(e).getDetailsList()) {
            if (detail.is(type)) {
                return detail.unpack(type);
            }
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    @Test
    @DisplayName("Thiếu identity: UNAUTHENTICATED + SERVICE_IDENTITY_MISSING, không retry")
    void missingIdentity() throws Exception {
        StatusRuntimeException e = catchStatus(() -> stub(null).getSku(GetSkuRequest.newBuilder().setSkuId("x").build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        ErrorInfo info = errorInfo(e);
        assertThat(info.getDomain()).isEqualTo("ecommerce.product.v1");
        assertThat(info.getReason()).isEqualTo(ProductGrpcError.SERVICE_IDENTITY_MISSING.name());
        assertThat(info.getMetadataOrThrow("retryable")).isEqualTo("false");
    }

    @Test
    @DisplayName("Identity ngoài allowlist: PERMISSION_DENIED + SERVICE_IDENTITY_NOT_ALLOWED")
    void identityNotAllowed() throws Exception {
        StatusRuntimeException e = catchStatus(() ->
                stub("checkout-service").getSku(GetSkuRequest.newBuilder().setSkuId("x").build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(errorInfo(e).getReason()).isEqualTo(ProductGrpcError.SERVICE_IDENTITY_NOT_ALLOWED.name());
    }

    @Test
    @DisplayName("sku_id sai: INVALID_ARGUMENT + SKU_ID_INVALID + BadRequest nêu field sku_id")
    void invalidSkuId() throws Exception {
        StatusRuntimeException e = catchStatus(() ->
                stub("cart-service").getSku(GetSkuRequest.newBuilder().setSkuId("not-a-uuid").build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(errorInfo(e).getReason()).isEqualTo(ProductGrpcError.SKU_ID_INVALID.name());
        assertThat(detail(e, BadRequest.class).getFieldViolations(0).getField()).isEqualTo("sku_id");
    }

    @Test
    @DisplayName("Quá 100 sku_ids: BATCH_TOO_LARGE kèm max_batch_size")
    void batchTooLarge() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(UUID.randomUUID().toString());
        }

        StatusRuntimeException e = catchStatus(() ->
                stub("cart-service").getBatchSkus(GetBatchSkusRequest.newBuilder().addAllSkuIds(ids).build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        ErrorInfo info = errorInfo(e);
        assertThat(info.getReason()).isEqualTo(ProductGrpcError.BATCH_TOO_LARGE.name());
        assertThat(info.getMetadataOrThrow("max_batch_size")).isEqualTo("100");
        assertThat(detail(e, BadRequest.class).getFieldViolations(0).getField()).isEqualTo("sku_ids");
    }

    @Test
    @DisplayName("Database lỗi: UNAVAILABLE + CATALOG_STORE_UNAVAILABLE + RetryInfo, không còn UNKNOWN")
    void storeFailure() throws Exception {
        when(skus.findById(any())).thenThrow(new DataAccessResourceFailureException("connection refused"));

        StatusRuntimeException e = catchStatus(() -> stub("cart-service")
                .getSku(GetSkuRequest.newBuilder().setSkuId(UUID.randomUUID().toString()).build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        ErrorInfo info = errorInfo(e);
        assertThat(info.getReason()).isEqualTo(ProductGrpcError.CATALOG_STORE_UNAVAILABLE.name());
        assertThat(info.getMetadataOrThrow("retryable")).isEqualTo("true");
        assertThat(detail(e, RetryInfo.class).getRetryDelay().getSeconds()).isEqualTo(2);
    }

    @Test
    @DisplayName("Lỗi không dự kiến: INTERNAL + INTERNAL_ERROR, không lộ nguyên nhân")
    void unexpectedFailure() throws Exception {
        when(skus.findById(any())).thenThrow(new IllegalStateException("secret detail"));

        StatusRuntimeException e = catchStatus(() -> stub("cart-service")
                .getSku(GetSkuRequest.newBuilder().setSkuId(UUID.randomUUID().toString()).build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(errorInfo(e).getReason()).isEqualTo(ProductGrpcError.INTERNAL_ERROR.name());
        assertThat(e.getStatus().getDescription()).doesNotContain("secret");
    }

    private static StatusRuntimeException catchStatus(Runnable call) {
        StatusRuntimeException caught = catchThrowableOfType(call::run, StatusRuntimeException.class);
        assertThat(caught).as("the call must fail with a gRPC status").isNotNull();
        return caught;
    }
}
