package com.platform.productservice.domain.statemachine.guards;

import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * Guard that validates the product is currently in ACTIVE state.
 * Applied to HIDE and AUTO_HIDE_OUT_OF_STOCK event transitions from ACTIVE.
 */
@Component
public class CanTransitionFromActiveGuard implements Guard<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(CanTransitionFromActiveGuard.class);

    @Override
    public boolean evaluate(StateContext<ProductState, ProductEvent> context) {
        ProductState currentState = context.getSource().getId();
        ProductEvent event = context.getEvent();

        if (currentState != ProductState.ACTIVE) {
            String msg = "Cannot send event " + event + " from state " + currentState +
                    ". This event is only valid from ACTIVE state.";
            log.warn("CanTransitionFromActiveGuard: {}", msg);
            context.getExtendedState().getVariables().put("guardError", msg);
            return false;
        }

        log.debug("CanTransitionFromActiveGuard: allowed {} from ACTIVE", event);
        return true;
    }
}
