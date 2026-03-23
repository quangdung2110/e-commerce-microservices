package com.ecommerce.shipping.infrastructure.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

// Theo thiết kế shipping_workflow.md mục 1 và technology_choice.md mục 4: Caffeine cache cho rate cards.
@Configuration
public class CaffeineCacheConfig {

	@Bean
	public CacheManager cacheManager() {
		CaffeineCacheManager cacheManager = new CaffeineCacheManager("shippingRateCards", "providerStatusMappings");
		cacheManager.setCaffeine(Caffeine.newBuilder()
				.expireAfterWrite(1, TimeUnit.HOURS)
				.maximumSize(20_000));
		return cacheManager;
	}
}
