package com.platform.productservice.domain.entity;

import com.platform.productservice.domain.vo.CategoryId;

import java.time.LocalDateTime;

/**
 * Category aggregate root.
 * Represents a hierarchical product category.
 */
public class Category {

    private CategoryId id;
    private CategoryId parentId;
    private String name;
    private String slug;
    private int level;
    private boolean isLeaf;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Category() {}

    public void initialize() {
        this.id = CategoryId.generate();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void makeLeaf() {
        this.isLeaf = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void makeNonLeaf() {
        this.isLeaf = false;
        this.updatedAt = LocalDateTime.now();
    }

    public void rename(String newName) {
        this.name = newName;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public boolean hasParent() {
        return parentId != null;
    }

    public boolean isLeafCategory() {
        return isLeaf;
    }

    public CategoryId getId() { return id; }
    public CategoryId getParentId() { return parentId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public int getLevel() { return level; }
    public boolean isLeaf() { return isLeaf; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(CategoryId id) { this.id = id; }
    public void setParentId(CategoryId parentId) { this.parentId = parentId; }
    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setLevel(int level) { this.level = level; }
    public void setLeaf(boolean leaf) { isLeaf = leaf; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
