package com.platform.productservice.domain.statemachine.guards;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * Guard that validates the product was NOT manually hidden by an admin.
 * Applied to AUTO_REACTIVATE_IN_STOCK event transition: HIDDEN -> ACTIVE.
 * Only allows auto-reactivation if adminHidden = false (i.e., product was hidden
 * by the system due to out-of-stock, not by manual admin action).
 */
@Component
public class IsNotAdminHiddenGuard implements Guard<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(IsNotAdminHiddenGuard.class);

    @Override
    public boolean evaluate(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.warn("IsNotAdminHiddenGuard: product not found in extended state");
            context.getExtendedState().getVariables().put("guardError",
                    "Product not found in state machine context");
            return false;
        }

        if (product.isAdminHidden()) {
            String msg = "Product " + product.getId().getValue() + " was manually hidden by admin. " +
                    "Auto-reactivation is not allowed. Use explicit SHOW action instead.";
            log.warn("IsNotAdminHiddenGuard: {}", msg);
            context.getExtendedState().getVariables().put("guardError", msg);
            return false;
        }

        log.debug("IsNotAdminHiddenGuard: product {} passed (not admin-hidden)", product.getId().getValue());
        return true;
    }
}
