package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a SKU code already exists.
 */
public class DuplicateSkuCodeException extends DomainException {

    public DuplicateSkuCodeException(String skuCode) {
        super("SKU code already exists: " + skuCode);
    }
}
