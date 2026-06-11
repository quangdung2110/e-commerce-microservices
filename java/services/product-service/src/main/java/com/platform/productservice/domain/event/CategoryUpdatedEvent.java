package com.platform.productservice.domain.event;

import com.platform.productservice.domain.entity.Category;

import java.util.List;

/**
 * Domain event fired when a category is updated.
 */
public class CategoryUpdatedEvent extends DomainEvent {

    private final String categoryId;
    private final String parentId;
    private final String name;
    private final String slug;
    private final int level;
    private final boolean isLeaf;
    private final List<String> changedFields;
    private final String updatedAt;

    public CategoryUpdatedEvent(Category category, List<String> changedFields) {
        super(
                "CATEGORY_UPDATED",
                "Category",
                category.getId().getValue(),
                category.getId().getValue()
        );
        this.categoryId = category.getId().getValue();
        this.parentId = category.getParentId() != null ? category.getParentId().getValue() : null;
        this.name = category.getName();
        this.slug = category.getSlug();
        this.level = category.getLevel();
        this.isLeaf = category.isLeaf();
        this.changedFields = changedFields;
        this.updatedAt = category.getUpdatedAt() != null ? category.getUpdatedAt().toString() : null;
    }

    public String getCategoryId() { return categoryId; }
    public String getParentId() { return parentId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public int getLevel() { return level; }
    public boolean isLeaf() { return isLeaf; }
    public List<String> getChangedFields() { return changedFields; }
    public String getUpdatedAt() { return updatedAt; }
}
