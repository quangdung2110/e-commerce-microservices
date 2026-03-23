package com.ecommerce.shipping.infrastructure.config;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.enums.ShipmentTransitionEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

// Theo thiết kế shipping_state_machine.md mục 1: shipment FSM states/transitions.
@Configuration
@EnableStateMachineFactory
public class ShipmentStateMachineConfig extends EnumStateMachineConfigurerAdapter<ShipmentStatus, ShipmentTransitionEvent> {

	@Override
	public void configure(StateMachineStateConfigurer<ShipmentStatus, ShipmentTransitionEvent> states) throws Exception {
		states.withStates()
				.initial(ShipmentStatus.PENDING)
				.states(EnumSet.allOf(ShipmentStatus.class))
				.end(ShipmentStatus.DELIVERED)
				.end(ShipmentStatus.RETURNED)
				.end(ShipmentStatus.CANCELLED);
	}

	@Override
	public void configure(StateMachineTransitionConfigurer<ShipmentStatus, ShipmentTransitionEvent> transitions) throws Exception {
		transitions
				.withExternal().source(ShipmentStatus.PENDING).target(ShipmentStatus.READY_TO_SHIP).event(ShipmentTransitionEvent.LABEL_CONFIRMED)
				.and()
				.withExternal().source(ShipmentStatus.READY_TO_SHIP).target(ShipmentStatus.IN_TRANSIT).event(ShipmentTransitionEvent.PICKED_UP)
				.and()
				.withExternal().source(ShipmentStatus.IN_TRANSIT).target(ShipmentStatus.DELIVERED).event(ShipmentTransitionEvent.DELIVERED)
				.and()
				.withExternal().source(ShipmentStatus.IN_TRANSIT).target(ShipmentStatus.FAILED).event(ShipmentTransitionEvent.DELIVERY_FAILED)
				.and()
				.withExternal().source(ShipmentStatus.FAILED).target(ShipmentStatus.IN_TRANSIT).event(ShipmentTransitionEvent.RETRY_DELIVERY)
				.and()
				.withExternal().source(ShipmentStatus.FAILED).target(ShipmentStatus.RETURNED).event(ShipmentTransitionEvent.RETURN_INITIATED)
				.and()
				.withExternal().source(ShipmentStatus.DELIVERED).target(ShipmentStatus.RETURNED).event(ShipmentTransitionEvent.RETURN_PICKUP)
				.and()
				.withExternal().source(ShipmentStatus.PENDING).target(ShipmentStatus.CANCELLED).event(ShipmentTransitionEvent.CANCEL_SHIPMENT);
	}
}
