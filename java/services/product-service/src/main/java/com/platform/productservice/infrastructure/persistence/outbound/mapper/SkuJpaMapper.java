package com.platform.productservice.infrastructure.persistence.outbound.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.persistence.entity.SkuJpaEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SkuJpaMapper {

    private final ObjectMapper objectMapper;

    public SkuJpaMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Sku toDomain(SkuJpaEntity entity) {
        if (entity == null) return null;

        Sku sku = new Sku();
        sku.setId(SkuId.of(entity.getSkuId()));
        sku.setProductId(ProductId.of(entity.getProductId()));
        sku.setSkuCode(entity.getSkuCode());
        sku.setOriginalPrice(entity.getOriginalPrice());
        sku.setPromotionalPrice(entity.getPromotionalPrice());
        sku.setStatus(entity.getStatus());
        sku.setDeleted(entity.getIsDeleted() != null && entity.getIsDeleted());
        sku.setDeletedAt(entity.getDeletedAt());
        sku.setDisplayQuantity(entity.getDisplayQuantity() != null ? entity.getDisplayQuantity() : 0);
        sku.setLastInventorySyncAt(entity.getLastInventorySyncAt() != null ? entity.getLastInventorySyncAt() : 0L);
        sku.setVariantAttributes(deserializeJson(entity.getVariantAttributesJson()));
        sku.setCreatedAt(entity.getCreatedAt());
        sku.setUpdatedAt(entity.getUpdatedAt());
        return sku;
    }

    public SkuJpaEntity toEntity(Sku sku) {
        if (sku == null) return null;

        SkuJpaEntity entity = new SkuJpaEntity();
        entity.setSkuId(sku.getId() != null ? sku.getId().getValue() : null);
        entity.setProductId(sku.getProductId() != null ? sku.getProductId().getValue() : null);
        entity.setSkuCode(sku.getSkuCode());
        entity.setOriginalPrice(sku.getOriginalPrice());
        entity.setPromotionalPrice(sku.getPromotionalPrice());
        entity.setStatus(sku.getStatus());
        entity.setIsDeleted(sku.isDeleted());
        entity.setDeletedAt(sku.getDeletedAt());
        entity.setDisplayQuantity(sku.getDisplayQuantity());
        entity.setLastInventorySyncAt(sku.getLastInventorySyncAt());
        entity.setVariantAttributesJson(serializeToMap(sku.getVariantAttributes()));
        entity.setCreatedAt(sku.getCreatedAt());
        entity.setUpdatedAt(sku.getUpdatedAt());
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeJson(Map<String, Object> data) {
        if (data == null) return new HashMap<>();
        return new HashMap<>(data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serializeToMap(Map<String, Object> data) {
        if (data == null) return new HashMap<>();
        return new HashMap<>(data);
    }
}
