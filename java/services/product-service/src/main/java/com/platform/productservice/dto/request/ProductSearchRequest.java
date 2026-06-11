package com.platform.productservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for product search with filtering and pagination.
 */
public record ProductSearchRequest(
        String categoryId,

        String status,

        String search,

        @Min(value = 1, message = "Page must be at least 1")
        Integer page,

        @Min(value = 1, message = "Size must be at least 1")
        @Max(value = 100, message = "Size must not exceed 100")
        Integer size,

        String sort
) {
    public ProductSearchRequest {
        if (page == null) page = 1;
        if (size == null) size = 20;
        if (sort == null) sort = "updatedAt,desc";
    }
}
