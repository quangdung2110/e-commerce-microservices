package com.ecommerce.shipping.domain.enums;

// Theo thiết kế shipping_state_machine.md mục 1.2: 8 transition events.
public enum ShipmentTransitionEvent {
    LABEL_CONFIRMED,
    PICKED_UP,
    DELIVERED,
    DELIVERY_FAILED,
    RETRY_DELIVERY,
    RETURN_INITIATED,
    RETURN_PICKUP,
    CANCEL_SHIPMENT
}
