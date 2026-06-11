package com.platform.productservice.dto.request;

import jakarta.annotation.Nullable;

/**
 * Request DTO for updating an existing category (partial update).
 */
public record UpdateCategoryRequest(
        @Nullable
        String name,

        @Nullable
        String slug,

        @Nullable
        String parentId,

        @Nullable
        Boolean isLeaf
) {}
