package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.domain.model.ShippingProvider;

import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_db_design.md mục 1.1: active providers catalog cho calculation/create shipment.
public interface ShippingProviderCatalogPort {

    List<ShippingProvider> findActiveProviders();

    Optional<ShippingProvider> findById(Integer providerId);

    Optional<ShippingProvider> findByCode(String providerCode);
}
