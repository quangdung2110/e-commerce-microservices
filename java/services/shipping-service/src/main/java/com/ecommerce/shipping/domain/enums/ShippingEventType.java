package com.ecommerce.shipping.domain.enums;

// Theo thiết kế shipping_events.md mục 3: produced event types.
public enum ShippingEventType {
    SHIPMENT_CREATED,
    SHIPMENT_STATUS_UPDATED,
    SHIPMENT_DELIVERED,
    SHIPMENT_RETURNED,
    SHIPMENT_CANCELLED,
    SHIPMENT_DELAYED
}
