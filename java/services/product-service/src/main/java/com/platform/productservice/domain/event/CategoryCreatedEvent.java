package com.platform.productservice.domain.event;

import com.platform.productservice.domain.entity.Category;

/**
 * Domain event fired when a category is created.
 * Partition key is category_id.
 */
public class CategoryCreatedEvent extends DomainEvent {

    private final String categoryId;
    private final String parentId;
    private final String name;
    private final String slug;
    private final int level;
    private final boolean isLeaf;

    public CategoryCreatedEvent(Category category) {
        super(
                "CATEGORY_CREATED",
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
    }

    public String getCategoryId() { return categoryId; }
    public String getParentId() { return parentId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public int getLevel() { return level; }
    public boolean isLeaf() { return isLeaf; }
}
