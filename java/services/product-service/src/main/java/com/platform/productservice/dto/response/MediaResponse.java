package com.platform.productservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for product or SKU media.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaResponse(
        String mediaId,
        String skuId,
        String mediaType,
        String url,
        String thumbnailUrl,
        Integer sortOrder
) {}
