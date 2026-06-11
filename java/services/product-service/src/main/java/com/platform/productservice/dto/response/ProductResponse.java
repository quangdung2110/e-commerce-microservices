package com.platform.productservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for product details.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductResponse(
        String productId,
        String name,
        String slug,
        String description,
        String categoryId,
        String categoryName,
        String status,
        boolean isDeleted,
        boolean isAdminHidden,
        Map<String, Object> attributes,
        List<SkuResponse> skus,
        List<MediaResponse> media,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
