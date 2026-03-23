package com.ecommerce.promotion.application.exception;

/**
 * Ném khi resource không tồn tại.
 * Maps to HTTP 404 Not Found.
 */
public class ResourceNotFoundException extends CoreBusinessException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super(
                String.format("%s not found with id: %s", resourceType, id),
                "RESOURCE_NOT_FOUND");
    }
}
