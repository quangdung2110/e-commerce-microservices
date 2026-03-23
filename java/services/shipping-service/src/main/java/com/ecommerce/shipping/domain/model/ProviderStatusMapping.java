package com.ecommerce.shipping.domain.model;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;

// Theo thiết kế shipping_state_machine.md mục 2: provider status mapping table.
public record ProviderStatusMapping(
        Integer providerId,
        String providerStatusCode,
        ShipmentStatus standardizedStatus
) {
}
