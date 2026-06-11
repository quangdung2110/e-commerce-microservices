package com.platform.productservice.infrastructure.persistence.outbound.mapper;

import com.platform.productservice.domain.entity.ProductMedia;
import com.platform.productservice.domain.vo.MediaId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.persistence.entity.ProductMediaJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class ProductMediaJpaMapper {

    public ProductMedia toDomain(ProductMediaJpaEntity entity) {
        if (entity == null) return null;

        ProductMedia media = new ProductMedia();
        media.setId(MediaId.of(entity.getMediaId()));
        if (entity.getSkuId() != null) {
            media.setSkuId(SkuId.of(entity.getSkuId()));
        }
        media.setMediaType(entity.getMediaType());
        media.setUrl(entity.getUrl());
        media.setThumbnailUrl(entity.getThumbnailUrl());
        media.setSortOrder(entity.getSortOrder() != null ? entity.getSortOrder() : 0);
        media.setCreatedAt(entity.getCreatedAt());
        media.setUpdatedAt(entity.getUpdatedAt());
        return media;
    }

    public ProductMediaJpaEntity toEntity(ProductMedia media) {
        if (media == null) return null;

        ProductMediaJpaEntity entity = new ProductMediaJpaEntity();
        entity.setMediaId(media.getId() != null ? media.getId().getValue() : null);
        entity.setSkuId(media.getSkuId() != null ? media.getSkuId().getValue() : null);
        entity.setMediaType(media.getMediaType());
        entity.setUrl(media.getUrl());
        entity.setThumbnailUrl(media.getThumbnailUrl());
        entity.setSortOrder(media.getSortOrder());
        entity.setCreatedAt(media.getCreatedAt());
        entity.setUpdatedAt(media.getUpdatedAt());
        return entity;
    }
}
