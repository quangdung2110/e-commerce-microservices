package com.platform.productservice.web.dto;

import com.platform.productservice.domain.enums.AttributeValueType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO của attribute cấu hình và option
 * (contracts/http/product/v1/openapi.yaml).
 */
public final class AttributeDtos {

    private AttributeDtos() {
    }

    public record AttributeOptionResponse(UUID optionId,
                                          UUID attributeId,
                                          String code,
                                          String label,
                                          int sortOrder,
                                          Instant createdAt,
                                          Instant updatedAt) {
    }

    public record AttributeResponse(UUID attributeId,
                                    String code,
                                    String name,
                                    AttributeValueType valueType,
                                    List<AttributeOptionResponse> options,
                                    Instant createdAt,
                                    Instant updatedAt) {
    }

    public record AttributeListResponse(List<AttributeResponse> items) {
    }

    public record CreateAttributeRequest(@NotBlank @Size(max = 64) String code,
                                         @NotBlank @Size(max = 120) String name,
                                         @NotNull AttributeValueType valueType) {
    }

    public record ReplaceAttributeRequest(@NotBlank @Size(max = 64) String code,
                                          @NotBlank @Size(max = 120) String name,
                                          @NotNull AttributeValueType valueType) {
    }

    public record CreateAttributeOptionRequest(@NotBlank @Size(max = 64) String code,
                                               @NotBlank @Size(max = 120) String label,
                                               @Min(0) Integer sortOrder) {
    }

    public record ReplaceAttributeOptionRequest(@NotBlank @Size(max = 64) String code,
                                                @NotBlank @Size(max = 120) String label,
                                                @NotNull @Min(0) Integer sortOrder) {
    }
}
