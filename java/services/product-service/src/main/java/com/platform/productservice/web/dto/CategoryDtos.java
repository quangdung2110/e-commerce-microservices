package com.platform.productservice.web.dto;

import com.platform.productservice.domain.enums.AttributeValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO của nhóm Category và mapping category–attribute
 * (contracts/http/product/v1/openapi.yaml).
 */
public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CategoryAttributeResponse(UUID categoryId,
                                            UUID attributeId,
                                            String attributeCode,
                                            String attributeName,
                                            AttributeValueType valueType,
                                            boolean isRequired,
                                            boolean isVariant) {
    }

    public record CategoryAttributeListResponse(List<CategoryAttributeResponse> items) {
    }

    public record CategoryResponse(UUID categoryId,
                                   UUID parentId,
                                   String name,
                                   String slug,
                                   long categoryRevision,
                                   List<CategoryAttributeResponse> attributes,
                                   Instant createdAt,
                                   Instant updatedAt) {
    }

    public record CategoryListResponse(List<CategoryResponse> items) {
    }

    public record CreateCategoryRequest(UUID parentId,
                                        @NotBlank @Size(max = 160) String name,
                                        @NotBlank @Size(max = 160) String slug) {
    }

    public record ReplaceCategoryRequest(UUID parentId,
                                         @NotBlank @Size(max = 160) String name,
                                         @NotBlank @Size(max = 160) String slug) {
    }

    public record PutCategoryAttributeRequest(@NotNull Boolean isRequired, @NotNull Boolean isVariant) {
    }

    public record CategoryPathSegment(UUID categoryId, String name, String slug) {
    }

    /** Item của internal category export: path và is_leaf dẫn xuất tại thời điểm đọc. */
    public record CategoryExportResponse(UUID categoryId,
                                         UUID parentId,
                                         String name,
                                         String slug,
                                         long categoryRevision,
                                         List<CategoryPathSegment> path,
                                         boolean isLeaf,
                                         Instant updatedAt) {
    }

    public record CategoryExportPageResponse(List<CategoryExportResponse> categories, String nextCursor) {
    }
}
