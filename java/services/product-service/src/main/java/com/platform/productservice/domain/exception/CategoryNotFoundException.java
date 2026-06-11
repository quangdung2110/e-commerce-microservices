package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a category is not found.
 */
public class CategoryNotFoundException extends DomainException {

    public CategoryNotFoundException(String categoryId) {
        super("Category not found: " + categoryId);
    }
}
