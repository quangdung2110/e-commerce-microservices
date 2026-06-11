package com.platform.productservice.port.inbound;

import com.platform.productservice.domain.entity.Product;
import org.springframework.data.domain.Page;

/**
 * Inbound port interface for product management operations.
 * Defines the contract for all product-related use cases.
 */
public interface ProductServicePort {

    /**
     * Creates a new product.
     *
     * @param product the product to create
     * @return the created product with generated ID
     */
    Product create(Product product);

    /**
     * Finds a product by its ID.
     *
     * @param productId the product ID
     * @return the product if found
     */
    Product findById(String productId);

    /**
     * Searches products by criteria with pagination.
     *
     * @param criteria the search criteria
     * @return paginated result of matching products
     */
    Page<Product> search(ProductSearchCriteria criteria);

    /**
     * Updates an existing product.
     *
     * @param productId the product ID to update
     * @param product   the updated product data
     * @return the updated product
     */
    Product update(String productId, Product product);

    /**
     * Soft-deletes a product.
     *
     * @param productId the product ID to delete
     */
    void delete(String productId);

    /**
     * Submits a product for activation (DRAFT -> ACTIVE).
     *
     * @param productId the product ID
     * @return the updated product with new status
     */
    Product submit(String productId);

    /**
     * Activates a product (admin-initiated).
     *
     * @param productId the product ID
     * @return the updated product with new status
     */
    Product activate(String productId);

    /**
     * Hides a product (ACTIVE -> HIDDEN).
     *
     * @param productId the product ID
     * @return the updated product with new status
     */
    Product hide(String productId);

    /**
     * Shows a hidden product (HIDDEN -> ACTIVE).
     *
     * @param productId the product ID
     * @return the updated product with new status
     */
    Product show(String productId);

    /**
     * Search criteria for product queries.
     *
     * @param categoryId filter by category ID
     * @param status     filter by product status
     * @param search     free-text search term
     * @param page       page number (0-indexed)
     * @param size       page size
     * @param sort       sort field and direction (e.g., "createdAt,desc")
     */
    record ProductSearchCriteria(
            String categoryId,
            String status,
            String search,
            int page,
            int size,
            String sort
    ) {}
}
