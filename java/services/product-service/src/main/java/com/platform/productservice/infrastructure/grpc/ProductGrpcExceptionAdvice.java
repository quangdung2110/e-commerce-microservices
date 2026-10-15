package com.platform.productservice.infrastructure.grpc;

import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Turns an exception that escapes a {@code ProductService} handler into a status with {@code ErrorInfo}
 * (contracts ADR-006). Without it a database failure reaches the caller as {@code UNKNOWN} with no reason.
 * Statuses the handlers return themselves are not touched.
 */
@GrpcAdvice
public class ProductGrpcExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProductGrpcExceptionAdvice.class);

    @GrpcExceptionHandler({DataAccessException.class, TransactionException.class})
    public StatusRuntimeException onStoreFailure(Exception e) {
        log.error("ProductService could not reach its catalog store", e);
        return ProductGrpcErrors.exception(ProductGrpcError.CATALOG_STORE_UNAVAILABLE, "the catalog store is unavailable");
    }

    @GrpcExceptionHandler(Exception.class)
    public StatusRuntimeException onUnexpected(Exception e) {
        log.error("unexpected failure in ProductService", e);
        return ProductGrpcErrors.exception(ProductGrpcError.INTERNAL_ERROR, "unexpected internal error");
    }
}
