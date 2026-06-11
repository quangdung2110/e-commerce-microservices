package com.platform.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for creating product or SKU media.
 */
public record CreateMediaRequest(
        String skuId,

        @NotBlank(message = "Media type is required")
        @Pattern(regexp = "IMAGE|VIDEO", message = "Media type must be IMAGE or VIDEO")
        String mediaType,

        @NotBlank(message = "URL is required")
        String url,

        String thumbnailUrl,

        Integer sortOrder
) {}
