package com.ecommerce.checkout.client;

import com.ecommerce.checkout.resilience.UpstreamStatusException;
import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts ADR-006: the reason Product sends reaches the logs, and a status without details still maps by code. */
class ProductClientErrorReasonTest {

    private static StatusRuntimeException withReason(Status.Code code, String domain, String reason) {
        com.google.rpc.Status status = com.google.rpc.Status.newBuilder()
                .setCode(code.value())
                .setMessage("free text")
                .addDetails(Any.pack(ErrorInfo.newBuilder().setDomain(domain).setReason(reason)
                        .putMetadata("retryable", "false").build()))
                .build();
        return StatusProto.toStatusRuntimeException(status);
    }

    @Test
    void aRefusedIdentityKeepsItsStatusMappingAndCarriesTheReason() {
        Throwable translated = ProductClient.translate(
                withReason(Status.Code.PERMISSION_DENIED, "ecommerce.product.v1", "SERVICE_IDENTITY_NOT_ALLOWED"));

        assertThat(translated).isInstanceOf(UpstreamStatusException.class);
        UpstreamStatusException upstream = (UpstreamStatusException) translated;
        assertThat(upstream.status()).isEqualTo(403);
        assertThat(upstream.code()).isEqualTo("SERVICE_IDENTITY_NOT_ALLOWED");
    }

    @Test
    void invalidArgumentAndUnauthenticatedCarryTheirReasons() {
        UpstreamStatusException batch = (UpstreamStatusException) ProductClient.translate(
                withReason(Status.Code.INVALID_ARGUMENT, "ecommerce.product.v1", "BATCH_TOO_LARGE"));
        UpstreamStatusException missing = (UpstreamStatusException) ProductClient.translate(
                withReason(Status.Code.UNAUTHENTICATED, "ecommerce.product.v1", "SERVICE_IDENTITY_MISSING"));

        assertThat(batch.status()).isEqualTo(400);
        assertThat(batch.code()).isEqualTo("BATCH_TOO_LARGE");
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.code()).isEqualTo("SERVICE_IDENTITY_MISSING");
    }

    @Test
    void anInternalErrorBecomesA503WithItsReason() {
        UpstreamStatusException upstream = (UpstreamStatusException) ProductClient.translate(
                withReason(Status.Code.INTERNAL, "ecommerce.product.v1", "INTERNAL_ERROR"));

        assertThat(upstream.status()).isEqualTo(503);
        assertThat(upstream.code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void anUnavailableStoreStaysATransportFailureAndNamesTheReason() {
        Throwable translated = ProductClient.translate(
                withReason(Status.Code.UNAVAILABLE, "ecommerce.product.v1", "CATALOG_STORE_UNAVAILABLE"));

        assertThat(translated).isInstanceOf(IOException.class)
                .hasMessageContaining("CATALOG_STORE_UNAVAILABLE");
    }

    @Test
    void aReasonFromAnotherDomainIsIgnored() {
        UpstreamStatusException upstream = (UpstreamStatusException) ProductClient.translate(
                withReason(Status.Code.PERMISSION_DENIED, "ecommerce.other.v1", "SOMETHING_ELSE"));

        assertThat(upstream.status()).isEqualTo(403);
        assertThat(upstream.code()).isNull();
    }

    @Test
    void aStatusWithoutDetailsMapsByCodeAlone() {
        assertThat(ProductClient.translate(Status.DEADLINE_EXCEEDED.asRuntimeException()))
                .isInstanceOf(TimeoutException.class);
        assertThat(ProductClient.translate(Status.UNAVAILABLE.asRuntimeException()))
                .isInstanceOf(IOException.class).hasMessage("Product unavailable");
        UpstreamStatusException denied = (UpstreamStatusException) ProductClient.translate(
                Status.PERMISSION_DENIED.withDescription("mesh said no").asRuntimeException());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isNull();
    }

    @Test
    void nonGrpcErrorsPassThrough() {
        IllegalStateException other = new IllegalStateException("boom");

        assertThat(ProductClient.translate(other)).isSameAs(other);
    }
}
