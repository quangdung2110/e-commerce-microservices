package com.ecommerce.shipping.application.port.out;

import com.ecommerce.shipping.domain.model.ProviderStatusMapping;

import java.util.List;
import java.util.Optional;

// Theo thiết kế shipping_state_machine.md mục 2: status mapping lookup.
public interface ProviderStatusMappingRepositoryPort {

	Optional<ProviderStatusMapping> findByProviderAndStatusCode(Integer providerId, String providerStatusCode);

	List<ProviderStatusMapping> findByProvider(Integer providerId);
}
