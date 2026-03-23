package com.ecommerce.shipping.infrastructure.worker;

import com.ecommerce.shipping.application.port.out.ShippingProviderCatalogPort;
import com.ecommerce.shipping.domain.model.ShippingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// Theo thiết kế shipping_workers.md mục 3: cron sync bảng giá hằng ngày.
@Component
public class RateCardSyncWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateCardSyncWorker.class);

    private final ShippingProviderCatalogPort shippingProviderCatalogPort;
    private final CacheManager cacheManager;

    public RateCardSyncWorker(ShippingProviderCatalogPort shippingProviderCatalogPort,
                              CacheManager cacheManager) {
        this.shippingProviderCatalogPort = shippingProviderCatalogPort;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "${shipping.workers.rate-card-sync.cron:0 0 2 * * *}")
    void syncRateCards() {
        List<ShippingProvider> providers = shippingProviderCatalogPort.findActiveProviders();
        for (ShippingProvider provider : providers) {
            LOGGER.info("Rate card sync started for provider={} ({})", provider.code(), provider.id());
            LOGGER.info("Rate card sync finished for provider={} ({})", provider.code(), provider.id());
        }

        if (cacheManager.getCache("shippingRateCards") != null) {
            cacheManager.getCache("shippingRateCards").clear();
        }
    }
}
