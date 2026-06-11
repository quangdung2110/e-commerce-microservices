package com.platform.productservice.domain.event;

import java.util.List;

/**
 * Domain event fired when a category is deleted.
 */
public class CategoryDeletedEvent extends DomainEvent {

    private final String categoryId;
    private final List<String> affectedProductIds;

    public CategoryDeletedEvent(String categoryId, List<String> affectedProductIds) {
        super(
                "CATEGORY_DELETED",
                "Category",
                categoryId,
                categoryId
        );
        this.categoryId = categoryId;
        this.affectedProductIds = affectedProductIds;
    }

    public String getCategoryId() { return categoryId; }
    public List<String> getAffectedProductIds() { return affectedProductIds; }
}
