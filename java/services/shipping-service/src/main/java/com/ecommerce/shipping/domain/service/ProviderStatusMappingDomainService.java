package com.ecommerce.shipping.domain.service;

import com.ecommerce.shipping.domain.enums.ShipmentStatus;
import com.ecommerce.shipping.domain.model.ProviderStatusMapping;

import java.util.List;
import java.util.Locale;

// Theo thiết kế shipping_state_machine.md mục 2: chuẩn hóa provider status.
public class ProviderStatusMappingDomainService {

	public ShipmentStatus mapToStandardizedStatus(List<ProviderStatusMapping> mappings,
												  String providerStatusCode) {
		if (providerStatusCode == null || providerStatusCode.isBlank()) {
			throw new IllegalArgumentException("providerStatusCode must not be blank");
		}
		String normalizedIncoming = normalize(providerStatusCode);
		return mappings.stream()
				.filter(mapping -> normalize(mapping.providerStatusCode()).equals(normalizedIncoming))
				.findFirst()
				.map(ProviderStatusMapping::standardizedStatus)
				.orElseThrow(() -> new IllegalStateException(
						"No provider status mapping found for status: " + providerStatusCode));
	}

	private String normalize(String source) {
		return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
	}
}
