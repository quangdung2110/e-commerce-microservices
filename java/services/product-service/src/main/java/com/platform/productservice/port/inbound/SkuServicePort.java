package com.platform.productservice.port.inbound;

import com.platform.productservice.domain.entity.Sku;

import java.util.List;

/**
 * Inbound port interface for SKU management operations.
 * Defines the contract for all SKU-related use cases.
 */
public interface SkuServicePort {

    /**
     * Creates a new SKU for a product.
     *
     * @param productId the product ID
     * @param sku       the SKU to create
     * @return the created SKU with generated ID
     */
    Sku createSku(String productId, Sku sku);

    /**
     * Updates an existing SKU.
     *
     * @param productId the product ID
     * @param skuId     the SKU ID to update
     * @param sku       the updated SKU data
     * @return the updated SKU
     */
    Sku updateSku(String productId, String skuId, Sku sku);

    /**
     * Soft-deletes a SKU.
     *
     * @param productId the product ID
     * @param skuId     the SKU ID to delete
     */
    void deleteSku(String productId, String skuId);

    /**
     * Finds a SKU by its ID.
     *
     * @param skuId the SKU ID
     * @return the SKU if found
     */
    Sku findById(String skuId);

    /**
     * Finds all SKUs belonging to a product.
     *
     * @param productId the product ID
     * @return list of SKUs
     */
    List<Sku> findByProductId(String productId);
}
