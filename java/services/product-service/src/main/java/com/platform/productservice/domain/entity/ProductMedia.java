package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.vo.MediaId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.domain.enums.MediaType;

import java.time.LocalDateTime;

/**
 * Product media entity.
 * Represents images or videos associated with a product or SKU.
 */
public class ProductMedia {

    private MediaId id;
    private SkuId skuId;
    private MediaType mediaType;
    private String url;
    private String thumbnailUrl;
    private int sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProductMedia() {}

    public void initialize() {
        this.id = MediaId.generate();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public MediaId getId() { return id; }
    public SkuId getSkuId() { return skuId; }
    public MediaType getMediaType() { return mediaType; }
    public String getUrl() { return url; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public int getSortOrder() { return sortOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(MediaId id) { this.id = id; }
    public void setSkuId(SkuId skuId) { this.skuId = skuId; }
    public void setMediaType(MediaType mediaType) { this.mediaType = mediaType; }
    public void setUrl(String url) { this.url = url; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
