package com.platform.productservice.domain.statemachine.guards;

import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * Guard that validates the product is currently in HIDDEN state.
 * Applied to SHOW and AUTO_REACTIVATE_IN_STOCK event transitions from HIDDEN.
 */
@Component
public class CanTransitionFromHiddenGuard implements Guard<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(CanTransitionFromHiddenGuard.class);

    @Override
    public boolean evaluate(StateContext<ProductState, ProductEvent> context) {
        ProductState currentState = context.getSource().getId();
        ProductEvent event = context.getEvent();

        if (currentState != ProductState.HIDDEN) {
            String msg = "Cannot send event " + event + " from state " + currentState +
                    ". This event is only valid from HIDDEN state.";
            log.warn("CanTransitionFromHiddenGuard: {}", msg);
            context.getExtendedState().getVariables().put("guardError", msg);
            return false;
        }

        log.debug("CanTransitionFromHiddenGuard: allowed {} from HIDDEN", event);
        return true;
    }
}
