package com.platform.productservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for creating a new product.
 */
public record CreateProductRequest(
        @NotBlank(message = "Product name is required")
        @Size(max = 255, message = "Product name must not exceed 255 characters")
        String name,

        @Size(max = 5000, message = "Description must not exceed 5000 characters")
        String description,

        @NotBlank(message = "Category ID is required")
        String categoryId,

        String slug,

        Map<String, Object> attributes,

        @Valid
        @NotNull(message = "SKUs are required")
        @NotEmpty(message = "At least one SKU is required")
        List<CreateSkuRequest> skus,

        @Valid
        List<CreateMediaRequest> media
) {}
