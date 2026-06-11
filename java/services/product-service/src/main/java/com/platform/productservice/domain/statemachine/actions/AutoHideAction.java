package com.platform.productservice.domain.statemachine.actions;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.enums.ChangeSource;
import com.platform.productservice.domain.enums.ProductStatus;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Action for AUTO_HIDE_OUT_OF_STOCK event: ACTIVE -> HIDDEN (system-initiated).
 * Sets product status to HIDDEN but does NOT set adminHidden = true,
 * so that the product can be auto-reactivated when stock returns.
 * Marks changedByType as SYSTEM so the transition is not attributed to admin.
 */
@Component
public class AutoHideAction implements Action<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(AutoHideAction.class);

    @Override
    public void execute(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.error("AutoHideAction: product not found in extended state");
            return;
        }

        product.setStatus(ProductStatus.HIDDEN);
        product.setUpdatedAt(LocalDateTime.now());
        context.getExtendedState().getVariables().put("product", product);
        context.getExtendedState().getVariables().put("changedBy", "AUTO_HIDE_OUT_OF_STOCK");
        context.getExtendedState().getVariables().put("changedByType", ChangeSource.AUTO_HIDE_OUT_OF_STOCK.name());
        context.getExtendedState().getVariables().put("transitionReason",
                "Auto-hide triggered: all SKUs are out of stock");

        log.info("AutoHideAction: product {} auto-hidden due to out-of-stock (adminHidden NOT set)", product.getId().getValue());
    }
}
