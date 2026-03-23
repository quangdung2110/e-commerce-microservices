package com.ecommerce.shipping.domain.enums;

// Theo thiết kế shipping_state_machine.md mục 1.1: standardized statuses.
public enum ShipmentStatus {
    PENDING,
    READY_TO_SHIP,
    IN_TRANSIT,
    DELIVERED,
    FAILED,
    RETURNED,
    CANCELLED
}
