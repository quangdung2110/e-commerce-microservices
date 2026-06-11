package com.platform.productservice.port.inbound;

import com.platform.productservice.domain.entity.Category;

import java.util.List;

/**
 * Inbound port interface for category management operations.
 * Defines the contract for all category-related use cases.
 */
public interface CategoryServicePort {

    /**
     * Creates a new category.
     *
     * @param category the category to create
     * @return the created category with generated ID
     */
    Category create(Category category);

    /**
     * Finds a category by its ID.
     *
     * @param categoryId the category ID
     * @return the category if found
     */
    Category findById(String categoryId);

    /**
     * Builds the full category tree starting from root categories.
     *
     * @return list of root categories with children populated
     */
    List<Category> findTree();

    /**
     * Finds all categories.
     *
     * @return list of all categories
     */
    List<Category> findAll();

    /**
     * Updates an existing category.
     *
     * @param categoryId the category ID to update
     * @param category   the updated category data
     * @return the updated category
     */
    Category update(String categoryId, Category category);

    /**
     * Soft-deletes a category.
     *
     * @param categoryId the category ID to delete
     */
    void delete(String categoryId);
}
