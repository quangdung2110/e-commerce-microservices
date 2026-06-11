package com.platform.productservice.domain.exception;

/**
 * Exception thrown when a slug already exists.
 */
public class DuplicateSlugException extends DomainException {

    public DuplicateSlugException(String slug) {
        super("Slug already exists: " + slug);
    }
}
