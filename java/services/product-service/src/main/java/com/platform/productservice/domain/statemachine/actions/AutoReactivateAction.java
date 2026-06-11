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
 * Action for AUTO_REACTIVATE_IN_STOCK event: HIDDEN -> ACTIVE (system-initiated).
 * Sets product status to ACTIVE but does NOT modify adminHidden flag,
 * preserving the admin's manual hide decision.
 * Marks changedByType as SYSTEM so the transition is not attributed to admin.
 */
@Component
public class AutoReactivateAction implements Action<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(AutoReactivateAction.class);

    @Override
    public void execute(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.error("AutoReactivateAction: product not found in extended state");
            return;
        }

        product.setStatus(ProductStatus.ACTIVE);
        product.setUpdatedAt(LocalDateTime.now());
        context.getExtendedState().getVariables().put("product", product);
        context.getExtendedState().getVariables().put("changedBy", "AUTO_REACTIVATE_IN_STOCK");
        context.getExtendedState().getVariables().put("changedByType", ChangeSource.AUTO_REACTIVATE_IN_STOCK.name());
        context.getExtendedState().getVariables().put("transitionReason",
                "Auto-reactivate triggered: stock returned while not admin-hidden");

        log.info("AutoReactivateAction: product {} auto-reactivated due to stock return (adminHidden preserved)", product.getId().getValue());
    }
}
