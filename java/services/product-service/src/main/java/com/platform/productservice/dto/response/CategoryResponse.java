package com.platform.productservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for category details.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryResponse(
        String categoryId,
        String parentId,
        String name,
        String slug,
        int level,
        boolean isLeaf,
        List<CategoryResponse> children,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
