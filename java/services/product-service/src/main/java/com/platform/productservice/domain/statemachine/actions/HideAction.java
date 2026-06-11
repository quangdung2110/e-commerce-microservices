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
 * Action for HIDE event (admin-initiated): ACTIVE -> HIDDEN.
 * Sets product status to HIDDEN and marks adminHidden = true.
 */
@Component
public class HideAction implements Action<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(HideAction.class);

    @Override
    public void execute(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.error("HideAction: product not found in extended state");
            return;
        }

        product.setStatus(ProductStatus.HIDDEN);
        product.setAdminHidden(true);
        product.setUpdatedAt(LocalDateTime.now());
        context.getExtendedState().getVariables().put("product", product);
        context.getExtendedState().getVariables().put("changedBy", "HIDE");
        context.getExtendedState().getVariables().put("changedByType", ChangeSource.ADMIN_SET_INACTIVE.name());
        context.getExtendedState().getVariables().put("transitionReason", "Admin manually hid the product");

        log.info("HideAction: product {} transitioned to HIDDEN (admin-initiated)", product.getId().getValue());
    }
}
