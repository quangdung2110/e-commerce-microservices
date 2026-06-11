package com.platform.productservice.infrastructure.persistence.outbound.mapper;

import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.persistence.entity.MarkdownAppliedJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class MarkdownAppliedJpaMapper {

    public MarkdownApplied toDomain(MarkdownAppliedJpaEntity entity) {
        if (entity == null) return null;

        MarkdownApplied markdown = new MarkdownApplied();
        markdown.setId(entity.getId());
        markdown.setProductId(entity.getProductId() != null ? ProductId.of(entity.getProductId()) : null);
        markdown.setRuleId(entity.getRuleId());
        markdown.setSkuId(entity.getSkuId() != null ? SkuId.of(entity.getSkuId()) : null);
        markdown.setDiscountType(entity.getDiscountType());
        markdown.setDiscountValue(entity.getDiscountValue());
        markdown.setMaxDiscountAmount(entity.getMaxDiscountAmount());
        markdown.setCalculatedPrice(entity.getCalculatedPrice());
        markdown.setAppliedAt(entity.getAppliedAt());
        markdown.setExpiresAt(entity.getExpiresAt());
        return markdown;
    }

    public MarkdownAppliedJpaEntity toEntity(MarkdownApplied markdown) {
        if (markdown == null) return null;

        MarkdownAppliedJpaEntity entity = new MarkdownAppliedJpaEntity();
        entity.setId(markdown.getId());
        entity.setProductId(markdown.getProductId() != null ? markdown.getProductId().getValue() : null);
        entity.setRuleId(markdown.getRuleId());
        entity.setSkuId(markdown.getSkuId() != null ? markdown.getSkuId().getValue() : null);
        entity.setDiscountType(markdown.getDiscountType());
        entity.setDiscountValue(markdown.getDiscountValue());
        entity.setMaxDiscountAmount(markdown.getMaxDiscountAmount());
        entity.setCalculatedPrice(markdown.getCalculatedPrice());
        entity.setAppliedAt(markdown.getAppliedAt());
        entity.setExpiresAt(markdown.getExpiresAt());
        return entity;
    }
}
