package com.platform.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new category.
 */
public record CreateCategoryRequest(
        @NotBlank(message = "Category name is required")
        @Size(max = 255, message = "Category name must not exceed 255 characters")
        String name,

        String slug,

        String parentId,

        Boolean isLeaf
) {}
