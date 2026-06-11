package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a SKU is not found.
 */
public class SkuNotFoundException extends DomainException {

    public SkuNotFoundException(String skuId) {
        super("SKU not found: " + skuId);
    }
}
