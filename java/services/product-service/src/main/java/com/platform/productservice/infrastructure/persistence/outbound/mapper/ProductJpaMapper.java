package com.platform.productservice.infrastructure.persistence.outbound.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.infrastructure.persistence.entity.ProductJpaEntity;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ProductJpaMapper {

    private final ObjectMapper objectMapper;

    public ProductJpaMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Product toDomain(ProductJpaEntity entity) {
        if (entity == null) return null;

        Product product = new Product();
        product.setId(ProductId.of(entity.getProductId()));
        product.setCategoryId(entity.getCategoryId() != null ? CategoryId.of(entity.getCategoryId()) : null);
        product.setName(entity.getName());
        product.setSlug(entity.getSlug());
        product.setDescription(entity.getDescription());
        product.setStatus(entity.getStatus());
        product.setDeleted(entity.getIsDeleted() != null && entity.getIsDeleted());
        product.setAdminHidden(entity.getAdminHidden() != null && entity.getAdminHidden());
        product.setDeletedAt(entity.getDeletedAt());
        product.setAttributes(deserializeJson(entity.getAttributesJson()));
        product.setCreatedAt(entity.getCreatedAt());
        product.setUpdatedAt(entity.getUpdatedAt());
        return product;
    }

    public ProductJpaEntity toEntity(Product product) {
        if (product == null) return null;

        ProductJpaEntity entity = new ProductJpaEntity();
        entity.setProductId(product.getId() != null ? product.getId().getValue() : null);
        entity.setCategoryId(product.getCategoryId() != null ? product.getCategoryId().getValue() : null);
        entity.setName(product.getName());
        entity.setSlug(product.getSlug() != null ? product.getSlug() : null);
        entity.setDescription(product.getDescription());
        entity.setStatus(product.getStatus());
        entity.setIsDeleted(product.isDeleted());
        entity.setAdminHidden(product.isAdminHidden());
        entity.setDeletedAt(product.getDeletedAt());
        entity.setAttributesJson(serializeToMap(product.getAttributes()));
        entity.setCreatedAt(product.getCreatedAt());
        entity.setUpdatedAt(product.getUpdatedAt());
        return entity;
    }

    private Map<String, Object> deserializeJson(Object data) {
        if (data == null) return new HashMap<>();
        if (data instanceof Map) {
            return objectMapper.convertValue(data, new TypeReference<Map<String, Object>>() {});
        }
        return new HashMap<>();
    }

    private Map<String, Object> serializeToMap(Object data) {
        if (data == null) return new HashMap<>();
        if (data instanceof Map) {
            return objectMapper.convertValue(data, new TypeReference<Map<String, Object>>() {});
        }
        return new HashMap<>();
    }
}
