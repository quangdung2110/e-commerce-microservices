package com.platform.productservice.dto.request;

import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * Request DTO for updating an existing product (partial update).
 */
public record UpdateProductRequest(
        @Nullable
        String name,

        @Nullable
        String slug,

        @Nullable
        String description,

        @Nullable
        String categoryId,

        @Nullable
        Map<String, Object> attributes
) {}
