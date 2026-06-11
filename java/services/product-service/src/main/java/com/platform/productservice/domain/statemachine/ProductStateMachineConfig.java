package com.platform.productservice.domain.statemachine;

import com.platform.productservice.domain.statemachine.actions.AutoHideAction;
import com.platform.productservice.domain.statemachine.actions.AutoReactivateAction;
import com.platform.productservice.domain.statemachine.actions.HideAction;
import com.platform.productservice.domain.statemachine.actions.ShowAction;
import com.platform.productservice.domain.statemachine.actions.SubmitAction;
import com.platform.productservice.domain.statemachine.guards.CanTransitionFromActiveGuard;
import com.platform.productservice.domain.statemachine.guards.CanTransitionFromHiddenGuard;
import com.platform.productservice.domain.statemachine.guards.HasAvailableStockGuard;
import com.platform.productservice.domain.statemachine.guards.HasRequiredSkusGuard;
import com.platform.productservice.domain.statemachine.guards.IsNotAdminHiddenGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;

import java.util.EnumSet;

/**
 * Spring State Machine configuration for Product lifecycle transitions.
 *
 * <p>State diagram:
 * <pre>
 *   DRAFT --[SUBMIT]--> ACTIVE
 *   ACTIVE --[HIDE]--> HIDDEN
 *   ACTIVE --[AUTO_HIDE_OUT_OF_STOCK]--> HIDDEN
 *   HIDDEN --[SHOW]--> ACTIVE
 *   HIDDEN --[AUTO_REACTIVATE_IN_STOCK]--> ACTIVE
 * </pre>
 *
 * <p>Guards enforce business rules on transitions.
 * Actions perform side effects and update the extended state.
 */
@Configuration
@EnableStateMachineFactory
public class ProductStateMachineConfig
        extends EnumStateMachineConfigurerAdapter<ProductState, ProductEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductStateMachineConfig.class);

    private final HasRequiredSkusGuard hasRequiredSkusGuard;
    private final HasAvailableStockGuard hasAvailableStockGuard;
    private final IsNotAdminHiddenGuard isNotAdminHiddenGuard;
    private final CanTransitionFromActiveGuard canTransitionFromActiveGuard;
    private final CanTransitionFromHiddenGuard canTransitionFromHiddenGuard;
    private final SubmitAction submitAction;
    private final HideAction hideAction;
    private final AutoHideAction autoHideAction;
    private final ShowAction showAction;
    private final AutoReactivateAction autoReactivateAction;

    public ProductStateMachineConfig(
            HasRequiredSkusGuard hasRequiredSkusGuard,
            HasAvailableStockGuard hasAvailableStockGuard,
            IsNotAdminHiddenGuard isNotAdminHiddenGuard,
            CanTransitionFromActiveGuard canTransitionFromActiveGuard,
            CanTransitionFromHiddenGuard canTransitionFromHiddenGuard,
            SubmitAction submitAction,
            HideAction hideAction,
            AutoHideAction autoHideAction,
            ShowAction showAction,
            AutoReactivateAction autoReactivateAction) {
        this.hasRequiredSkusGuard = hasRequiredSkusGuard;
        this.hasAvailableStockGuard = hasAvailableStockGuard;
        this.isNotAdminHiddenGuard = isNotAdminHiddenGuard;
        this.canTransitionFromActiveGuard = canTransitionFromActiveGuard;
        this.canTransitionFromHiddenGuard = canTransitionFromHiddenGuard;
        this.submitAction = submitAction;
        this.hideAction = hideAction;
        this.autoHideAction = autoHideAction;
        this.showAction = showAction;
        this.autoReactivateAction = autoReactivateAction;
    }

    @Override
    public void configure(StateMachineConfigurationConfigurer<ProductState, ProductEvent> config)
            throws Exception {
        config
                .withConfiguration()
                .autoStartup(false)
                .listener(stateMachineListener());
    }

    @Override
    public void configure(StateMachineStateConfigurer<ProductState, ProductEvent> states)
            throws Exception {
        states
                .withStates()
                .initial(ProductState.DRAFT)
                .states(EnumSet.allOf(ProductState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<ProductState, ProductEvent> transitions)
            throws Exception {
        transitions
                // DRAFT -> ACTIVE: admin submits product (requires at least 1 valid SKU)
                .withExternal()
                .source(ProductState.DRAFT)
                .target(ProductState.ACTIVE)
                .event(ProductEvent.SUBMIT)
                .guard(hasRequiredSkusGuard)
                .action(submitAction)
                .and()

                // ACTIVE -> HIDDEN: admin hides product
                .withExternal()
                .source(ProductState.ACTIVE)
                .target(ProductState.HIDDEN)
                .event(ProductEvent.HIDE)
                .guard(canTransitionFromActiveGuard)
                .action(hideAction)
                .and()

                // ACTIVE -> HIDDEN: system auto-hides when all SKUs out of stock
                .withExternal()
                .source(ProductState.ACTIVE)
                .target(ProductState.HIDDEN)
                .event(ProductEvent.AUTO_HIDE_OUT_OF_STOCK)
                .guard(canTransitionFromActiveGuard)
                .action(autoHideAction)
                .and()

                // HIDDEN -> ACTIVE: admin shows product (requires available stock)
                .withExternal()
                .source(ProductState.HIDDEN)
                .target(ProductState.ACTIVE)
                .event(ProductEvent.SHOW)
                .guard(canTransitionFromHiddenGuard)
                .guard(hasAvailableStockGuard)
                .action(showAction)
                .and()

                // HIDDEN -> ACTIVE: system auto-reactivates when stock returns and not admin-hidden
                .withExternal()
                .source(ProductState.HIDDEN)
                .target(ProductState.ACTIVE)
                .event(ProductEvent.AUTO_REACTIVATE_IN_STOCK)
                .guard(canTransitionFromHiddenGuard)
                .guard(isNotAdminHiddenGuard)
                .action(autoReactivateAction);
    }

    private StateMachineListener<ProductState, ProductEvent> stateMachineListener() {
        return new StateMachineListenerAdapter<>() {
            @Override
            public void stateChanged(State<ProductState, ProductEvent> from,
                                     State<ProductState, ProductEvent> to) {
                String fromState = from != null ? from.getId().name() : "NONE";
                String toState = to != null ? to.getId().name() : "NONE";
                log.debug("StateMachine: {} -> {}", fromState, toState);
            }

            @Override
            public void eventNotAccepted(
                    org.springframework.messaging.Message<ProductEvent> event) {
                log.warn("StateMachine: event {} was not accepted", event.getPayload());
            }

            @Override
            public void stateMachineError(org.springframework.statemachine.StateMachine<ProductState, ProductEvent> sm,
                                         Exception exception) {
                log.error("StateMachine: error in state machine {}: {}", sm.getId(), exception.getMessage(), exception);
            }
        };
    }
}
