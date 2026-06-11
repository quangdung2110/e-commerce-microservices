package com.platform.productservice.domain.statemachine.guards;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import com.platform.productservice.port.outbound.database.SkuRepository;
import com.platform.productservice.domain.vo.SkuId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Guard that validates a product has at least one SKU with originalPrice > 0 and not deleted.
 * Applied to SUBMIT event transition: DRAFT -> ACTIVE.
 */
@Component
public class HasRequiredSkusGuard implements Guard<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(HasRequiredSkusGuard.class);

    private final SkuRepository skuRepository;

    public HasRequiredSkusGuard(SkuRepository skuRepository) {
        this.skuRepository = skuRepository;
    }

    @Override
    public boolean evaluate(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.warn("HasRequiredSkusGuard: product not found in extended state");
            context.getExtendedState().getVariables().put("guardError",
                    "Product not found in state machine context");
            return false;
        }

        String productId = product.getId().getValue();

        List<SkuId> skuIds = product.getSkus() == null ? List.of()
                : product.getSkus().stream()
                        .filter(sku -> !sku.isDeleted())
                        .map(sku -> sku.getId())
                        .toList();

        if (skuIds.isEmpty()) {
            log.debug("HasRequiredSkusGuard: product {} has no SKUs", productId);
            String msg = "Product " + productId + " has no SKUs. At least one SKU is required to submit.";
            context.getExtendedState().getVariables().put("guardError", msg);
            return false;
        }

        List<SkuId> finalSkuIds = skuIds;
        boolean hasValidSku = skuRepository.findByIds(skuIds).stream()
                .anyMatch(sku -> !sku.isDeleted()
                        && sku.getOriginalPrice() != null
                        && sku.getOriginalPrice().compareTo(BigDecimal.ZERO) > 0);

        if (!hasValidSku) {
            String msg = "Product " + productId + " has no SKU with originalPrice > 0. At least one valid SKU is required to submit.";
            log.warn("HasRequiredSkusGuard: {}", msg);
            context.getExtendedState().getVariables().put("guardError", msg);
            return false;
        }

        log.debug("HasRequiredSkusGuard: product {} passed validation", productId);
        return true;
    }
}
