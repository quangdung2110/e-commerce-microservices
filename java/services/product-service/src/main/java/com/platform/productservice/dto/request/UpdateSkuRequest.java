package com.platform.productservice.dto.request;

import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Request DTO for updating an existing SKU (partial update).
 */
public record UpdateSkuRequest(
        @Nullable
        String skuCode,

        @Nullable
        @DecimalMin(value = "0.01", message = "Original price must be at least 0.01")
        BigDecimal originalPrice,

        @Nullable
        Map<String, String> variantAttributes,

        @Valid
        @Nullable
        List<CreateMediaRequest> media,

        @Valid
        @Nullable
        List<CreateMediaRequest> addMedia,

        @Nullable
        List<String> removeMediaIds
) {}
