package com.platform.productservice.infrastructure.grpc;

import io.grpc.StatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Turns an exception that escapes a {@code ProductService} handler into a status with {@code ErrorInfo}
 * (contracts ADR-006). Without it a database failure reaches the caller as {@code UNKNOWN} with no reason.
 * Statuses the handlers return themselves are not touched.
 */
@Component
public class ProductGrpcExceptionAdvice implements GrpcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductGrpcExceptionAdvice.class);

    @Override
    public StatusException handleException(Throwable failure) {
        if (failure instanceof DataAccessException || failure instanceof TransactionException) {
            log.error("ProductService could not reach its catalog store", failure);
            var error = ProductGrpcErrors.close(ProductGrpcError.CATALOG_STORE_UNAVAILABLE,
                    "the catalog store is unavailable");
            return error.status().asException(error.trailers());
        }

        log.error("unexpected failure in ProductService", failure);
        var error = ProductGrpcErrors.close(ProductGrpcError.INTERNAL_ERROR, "unexpected internal error");
        return error.status().asException(error.trailers());
    }
}
