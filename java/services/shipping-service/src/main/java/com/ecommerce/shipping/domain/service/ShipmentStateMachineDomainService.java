package com.ecommerce.shipping.domain.service;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.enums.ShipmentTransitionEvent;

import java.util.EnumMap;
import java.util.Map;

// Theo thiết kế shipping_state_machine.md mục 1.2 và 1.3: transition guards + retry rules.
public class ShipmentStateMachineDomainService {

	private static final int MAX_FAILED_RETRY = 3;

	private static final Map<ShipmentStatus, Map<ShipmentTransitionEvent, ShipmentStatus>> TRANSITIONS =
			new EnumMap<>(ShipmentStatus.class);

	static {
		register(ShipmentStatus.PENDING, ShipmentTransitionEvent.LABEL_CONFIRMED, ShipmentStatus.READY_TO_SHIP);
		register(ShipmentStatus.PENDING, ShipmentTransitionEvent.CANCEL_SHIPMENT, ShipmentStatus.CANCELLED);

		register(ShipmentStatus.READY_TO_SHIP, ShipmentTransitionEvent.PICKED_UP, ShipmentStatus.IN_TRANSIT);

		register(ShipmentStatus.IN_TRANSIT, ShipmentTransitionEvent.DELIVERED, ShipmentStatus.DELIVERED);
		register(ShipmentStatus.IN_TRANSIT, ShipmentTransitionEvent.DELIVERY_FAILED, ShipmentStatus.FAILED);

		register(ShipmentStatus.FAILED, ShipmentTransitionEvent.RETRY_DELIVERY, ShipmentStatus.IN_TRANSIT);
		register(ShipmentStatus.FAILED, ShipmentTransitionEvent.RETURN_INITIATED, ShipmentStatus.RETURNED);

		register(ShipmentStatus.DELIVERED, ShipmentTransitionEvent.RETURN_PICKUP, ShipmentStatus.RETURNED);
	}

	public boolean canTransition(ShipmentStatus currentStatus, ShipmentTransitionEvent event, int failedRetryCount) {
		if (currentStatus == null || event == null) {
			return false;
		}
		if (currentStatus == ShipmentStatus.DELIVERED
				|| currentStatus == ShipmentStatus.RETURNED
				|| currentStatus == ShipmentStatus.CANCELLED) {
			return false;
		}
		if (currentStatus == ShipmentStatus.FAILED
				&& event == ShipmentTransitionEvent.RETRY_DELIVERY
				&& failedRetryCount >= MAX_FAILED_RETRY) {
			return false;
		}
		Map<ShipmentTransitionEvent, ShipmentStatus> nextStates = TRANSITIONS.get(currentStatus);
		return nextStates != null && nextStates.containsKey(event);
	}

	public ShipmentStatus resolveNextStatus(ShipmentStatus currentStatus,
											ShipmentTransitionEvent event,
											int failedRetryCount) {
		if (!canTransition(currentStatus, event, failedRetryCount)) {
			throw new IllegalStateException("Invalid transition: " + currentStatus + " -> " + event);
		}
		return TRANSITIONS.get(currentStatus).get(event);
	}

	private static void register(ShipmentStatus from, ShipmentTransitionEvent event, ShipmentStatus to) {
		TRANSITIONS.computeIfAbsent(from, ignored -> new EnumMap<>(ShipmentTransitionEvent.class)).put(event, to);
	}
}
