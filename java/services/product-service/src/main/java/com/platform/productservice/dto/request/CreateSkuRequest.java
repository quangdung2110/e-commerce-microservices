package com.platform.productservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for creating a new SKU.
 */
public record CreateSkuRequest(
        @NotBlank(message = "SKU code is required")
        @Size(max = 255, message = "SKU code must not exceed 255 characters")
        String skuCode,

        @NotNull(message = "Original price is required")
        @DecimalMin(value = "0.01", message = "Original price must be at least 0.01")
        BigDecimal originalPrice,

        Map<String, String> variantAttributes,

        @Valid
        List<CreateMediaRequest> media
) {}
