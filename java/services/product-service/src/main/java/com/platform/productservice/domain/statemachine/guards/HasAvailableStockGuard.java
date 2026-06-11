package com.platform.productservice.domain.statemachine.guards;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import com.platform.productservice.port.outbound.database.SkuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * Guard that validates that SUM(displayQuantity) > 0 across all non-deleted SKUs.
 * Applied to SHOW event transition: HIDDEN -> ACTIVE.
 */
@Component
public class HasAvailableStockGuard implements Guard<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(HasAvailableStockGuard.class);

    private final SkuRepository skuRepository;

    public HasAvailableStockGuard(SkuRepository skuRepository) {
        this.skuRepository = skuRepository;
    }

    @Override
    public boolean evaluate(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.warn("HasAvailableStockGuard: product not found in extended state");
            context.getExtendedState().getVariables().put("guardError",
                    "Product not found in state machine context");
            return false;
        }

        String productId = product.getId().getValue();

        if (product.getSkus() != null && !product.getSkus().isEmpty()) {
            int totalQuantity = product.getSkus().stream()
                    .filter(sku -> !sku.isDeleted())
                    .mapToInt(sku -> sku.getDisplayQuantity())
                    .sum();

            if (totalQuantity > 0) {
                log.debug("HasAvailableStockGuard: product {} has {} total display quantity", productId, totalQuantity);
                return true;
            }
        }

        int totalQuantity = skuRepository.sumDisplayQuantityByProductId(product.getId());

        if (totalQuantity <= 0) {
            String msg = "Product " + productId + " has no available stock (total display quantity = 0). At least one SKU must have stock to show.";
            log.warn("HasAvailableStockGuard: {}", msg);
            context.getExtendedState().getVariables().put("guardError", msg);
            return false;
        }

        log.debug("HasAvailableStockGuard: product {} passed validation with {} total quantity", productId, totalQuantity);
        return true;
    }
}
