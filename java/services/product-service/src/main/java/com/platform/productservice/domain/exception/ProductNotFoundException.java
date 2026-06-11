package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a product is not found.
 */
public class ProductNotFoundException extends DomainException {

    public ProductNotFoundException(String productId) {
        super("Product not found: " + productId);
    }
}
