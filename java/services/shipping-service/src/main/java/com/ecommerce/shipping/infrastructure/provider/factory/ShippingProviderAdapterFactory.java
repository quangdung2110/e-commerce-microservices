package com.ecommerce.shipping.infrastructure.provider.factory;

import com.ecommerce.shipping.domain.adapter.ShippingProviderAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Theo thiết kế shipping_workflow.md mục 5: factory chọn adapter theo courier/provider.
@Component
public class ShippingProviderAdapterFactory {

	private final Map<String, ShippingProviderAdapter> adapterByCode;

	public ShippingProviderAdapterFactory(List<ShippingProviderAdapter> adapters) {
		this.adapterByCode = adapters.stream().collect(Collectors.toMap(
				adapter -> normalize(adapter.providerCode()),
				Function.identity()));
	}

	public ShippingProviderAdapter getAdapter(String providerCode) {
		ShippingProviderAdapter adapter = adapterByCode.get(normalize(providerCode));
		if (adapter == null) {
			throw new IllegalArgumentException("No shipping provider adapter found for code: " + providerCode);
		}
		return adapter;
	}

	private String normalize(String providerCode) {
		return providerCode == null ? "" : providerCode.trim().toUpperCase(Locale.ROOT);
	}
}
