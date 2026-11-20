package com.platform.productservice.infrastructure.grpc;

import io.grpc.Status;

/**
 * Stable error vocabulary of {@code ProductService} (contracts ADR-006). The same entries are listed in
 * {@code contracts/grpc/ecommerce/product/v1/errors.yaml}; {@code ProductGrpcErrorCatalogTest} fails when they drift.
 */
public enum ProductGrpcError {
    SERVICE_IDENTITY_MISSING(Status.Code.UNAUTHENTICATED, false),
    SERVICE_IDENTITY_NOT_ALLOWED(Status.Code.PERMISSION_DENIED, false),
    SKU_ID_INVALID(Status.Code.INVALID_ARGUMENT, false),
    BATCH_TOO_LARGE(Status.Code.INVALID_ARGUMENT, false),
    CATALOG_STORE_UNAVAILABLE(Status.Code.UNAVAILABLE, true),
    INTERNAL_ERROR(Status.Code.INTERNAL, false);

    /** {@code ErrorInfo.domain}: the proto package of the service. */
    public static final String DOMAIN = "ecommerce.product.v1";

    private final Status.Code status;
    private final boolean retryable;

    ProductGrpcError(Status.Code status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public Status.Code status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
