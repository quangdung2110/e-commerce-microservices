package com.platform.productservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for SKU details.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkuResponse(
        String skuId,
        String skuCode,
        BigDecimal originalPrice,
        BigDecimal promotionalPrice,
        BigDecimal bestPrice,
        boolean hasPromotion,
        String status,
        int displayQuantity,
        Map<String, Object> variantAttributes,
        List<MediaResponse> media,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
