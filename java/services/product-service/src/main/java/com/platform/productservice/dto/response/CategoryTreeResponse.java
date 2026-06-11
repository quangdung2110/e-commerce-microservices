package com.platform.productservice.dto.response;

import java.util.List;

/**
 * Response DTO for the full category tree.
 * Contains a flat list of root categories with nested children.
 */
public record CategoryTreeResponse(
        List<CategoryResponse> roots
) {}
