package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.exception.DomainException;
import com.platform.productservice.domain.exception.InvalidStateTransitionException;
import com.platform.productservice.domain.vo.CategoryId;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Product aggregate root.
 * Contains business methods for product lifecycle management.
 */
public class Product {

    private ProductId id;
    private CategoryId categoryId;
    private String name;
    private String slug;
    private String description;
    private ProductStatus status;
    private boolean isDeleted;
    private boolean adminHidden;
    private LocalDateTime deletedAt;
    private Map<String, Object> attributes;
    private List<Sku> skus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Product() {
        this.skus = new ArrayList<>();
        this.attributes = new java.util.HashMap<>();
    }

    public void initialize() {
        this.id = ProductId.generate();
        this.status = ProductStatus.DRAFT;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void submit() {
        if (this.status != ProductStatus.DRAFT) {
            throw new InvalidStateTransitionException(
                    ProductStatus.DRAFT.name(), this.status.name(),
                    "Product can only be submitted from DRAFT status");
        }
        if (!hasValidSkus()) {
            throw new DomainException("Product must have at least one SKU with originalPrice > 0 to submit");
        }
        this.status = ProductStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void hide() {
        if (this.status == ProductStatus.HIDDEN) {
            return;
        }
        if (this.status != ProductStatus.ACTIVE && this.status != ProductStatus.DRAFT) {
            throw new InvalidStateTransitionException(
                    this.status.name(), ProductStatus.HIDDEN.name(),
                    "Product can only be hidden from ACTIVE or DRAFT status");
        }
        this.status = ProductStatus.HIDDEN;
        this.adminHidden = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void show() {
        if (totalDisplayQuantity() <= 0) {
            throw new DomainException("Product cannot be shown when all SKUs are out of stock");
        }
        if (this.status != ProductStatus.HIDDEN) {
            throw new InvalidStateTransitionException(
                    this.status.name(), ProductStatus.ACTIVE.name(),
                    "Product can only be shown from HIDDEN status");
        }
        this.status = ProductStatus.ACTIVE;
        this.adminHidden = false;
        this.updatedAt = LocalDateTime.now();
    }

    public void autoHideOutOfStock() {
        if (this.status == ProductStatus.ACTIVE && allSkusOutOfStock()) {
            this.status = ProductStatus.HIDDEN;
            this.updatedAt = LocalDateTime.now();
        }
    }

    public void autoReactivateInStock() {
        if (this.status == ProductStatus.HIDDEN && !adminHidden && anySkuInStock()) {
            this.status = ProductStatus.ACTIVE;
            this.updatedAt = LocalDateTime.now();
        }
    }

    public void markDeleted() {
        this.isDeleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    public boolean hasValidSkus() {
        return skus != null && skus.stream()
                .anyMatch(sku -> !sku.isDeleted() &&
                        sku.getOriginalPrice() != null &&
                        sku.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0);
    }

    public boolean isActive() {
        return this.status == ProductStatus.ACTIVE;
    }

    public Optional<SkuId> getPrimarySkuId() {
        return skus != null && !skus.isEmpty()
                ? Optional.ofNullable(skus.get(0).getId())
                : Optional.empty();
    }

    public int totalDisplayQuantity() {
        if (skus == null) return 0;
        return skus.stream()
                .filter(sku -> !sku.isDeleted())
                .mapToInt(sku -> sku.getDisplayQuantity() != null ? sku.getDisplayQuantity() : 0)
                .sum();
    }

    public boolean canBeDeleted() {
        return this.status == ProductStatus.DRAFT || this.status == ProductStatus.HIDDEN;
    }

    public void addSku(Sku sku) {
        if (this.skus == null) {
            this.skus = new ArrayList<>();
        }
        this.skus.add(sku);
    }

    public void removeSku(SkuId skuId) {
        if (this.skus == null) return;
        this.skus.removeIf(sku -> sku.getId().equals(skuId));
    }

    public void recalculateBestPrices() {
        if (skus == null) return;
        for (Sku sku : skus) {
            if (!sku.isDeleted()) {
                BigDecimal bestPrice = sku.getBestPrice();
            }
        }
    }

    private boolean allSkusOutOfStock() {
        if (skus == null || skus.isEmpty()) return true;
        return skus.stream().filter(sku -> !sku.isDeleted())
                .allMatch(sku -> sku.getDisplayQuantity() == 0);
    }

    private boolean anySkuInStock() {
        if (skus == null || skus.isEmpty()) return false;
        return skus.stream().filter(sku -> !sku.isDeleted())
                .anyMatch(sku -> sku.getDisplayQuantity() > 0);
    }

    public ProductId getId() { return id; }
    public CategoryId getCategoryId() { return categoryId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public ProductStatus getStatus() { return status; }
    public boolean isDeleted() { return isDeleted; }
    public boolean isAdminHidden() { return adminHidden; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public Map<String, Object> getAttributes() { return attributes; }
    public List<Sku> getSkus() { return skus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(ProductId id) { this.id = id; }
    public void setCategoryId(CategoryId categoryId) { this.categoryId = categoryId; }
    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setDescription(String description) { this.description = description; }
    public void setStatus(ProductStatus status) { this.status = status; }
    public void setDeleted(boolean deleted) { isDeleted = deleted; }
    public void setAdminHidden(boolean adminHidden) { this.adminHidden = adminHidden; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? attributes : new java.util.HashMap<>();
    }
    public void setSkus(List<Sku> skus) {
        this.skus = skus != null ? skus : new ArrayList<>();
    }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
