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
 * Action for SHOW event (admin-initiated): HIDDEN -> ACTIVE.
 * Sets product status to ACTIVE and resets adminHidden = false.
 */
@Component
public class ShowAction implements Action<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(ShowAction.class);

    @Override
    public void execute(StateContext<ProductState, ProductEvent> context) {
        Product product = context.getExtendedState().get("product", Product.class);
        if (product == null) {
            log.error("ShowAction: product not found in extended state");
            return;
        }

        product.setStatus(ProductStatus.ACTIVE);
        product.setAdminHidden(false);
        product.setUpdatedAt(LocalDateTime.now());
        context.getExtendedState().getVariables().put("product", product);
        context.getExtendedState().getVariables().put("changedBy", "SHOW");
        context.getExtendedState().getVariables().put("changedByType", ChangeSource.ADMIN_SET_ACTIVE.name());
        context.getExtendedState().getVariables().put("transitionReason", "Admin manually showed the product");

        log.info("ShowAction: product {} transitioned to ACTIVE (admin-initiated)", product.getId().getValue());
    }
}
