package com.platform.productservice.domain.statemachine;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.service.DomainServiceMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Pure domain service that determines whether an FSM trigger should be fired
 * based on inventory changes. This is used by the Inventory Sync Phase 2 worker
 * to decide whether to auto-hide or auto-reactivate a product.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>If all SKUs have displayQuantity == 0 and product is ACTIVE and adminHidden == false
 *       -> return AUTO_HIDE_OUT_OF_STOCK</li>
 *   <li>If any SKU has displayQuantity > 0 and product is HIDDEN and adminHidden == false
 *       -> return AUTO_REACTIVATE_IN_STOCK</li>
 * </ul>
 */
@DomainServiceMarker
public class FsmTriggerCalculator {

    private static final Logger log = LoggerFactory.getLogger(FsmTriggerCalculator.class);

    /**
     * Determines if an FSM trigger event should be fired for the given product and SKUs.
     *
     * @param product the product entity
     * @param skus    the list of SKUs (should be non-deleted)
     * @return Optional containing the trigger event, or empty if no trigger is needed
     */
    public Optional<ProductEvent> calculateFsmTrigger(Product product, List<Sku> skus) {
        if (product == null) {
            log.warn("FsmTriggerCalculator: product is null, returning empty");
            return Optional.empty();
        }

        boolean allOutOfStock = skus == null || skus.stream()
                .filter(sku -> !sku.isDeleted())
                .allMatch(sku -> {
                    Integer qty = sku.getDisplayQuantity();
                    return qty != null && qty <= 0;
                });

        boolean anyInStock = skus != null && skus.stream()
                .filter(sku -> !sku.isDeleted())
                .anyMatch(sku -> {
                    Integer qty = sku.getDisplayQuantity();
                    return qty != null && qty > 0;
                });

        if (allOutOfStock
                && product.getStatus() == ProductStatus.ACTIVE
                && !product.isAdminHidden()) {
            log.info("FsmTriggerCalculator: product {} triggers AUTO_HIDE_OUT_OF_STOCK", product.getId().getValue());
            return Optional.of(ProductEvent.AUTO_HIDE_OUT_OF_STOCK);
        }

        if (anyInStock
                && product.getStatus() == ProductStatus.HIDDEN
                && !product.isAdminHidden()) {
            log.info("FsmTriggerCalculator: product {} triggers AUTO_REACTIVATE_IN_STOCK", product.getId().getValue());
            return Optional.of(ProductEvent.AUTO_REACTIVATE_IN_STOCK);
        }

        log.debug("FsmTriggerCalculator: no trigger needed for product {} (status={}, adminHidden={}, anyInStock={})",
                product.getId().getValue(), product.getStatus(), product.isAdminHidden(), anyInStock);
        return Optional.empty();
    }
}
