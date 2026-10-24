package com.ecommerce.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The Product-level read projection {@code product_cards} (ADR-027): one document per Product with
 * its SKU documents nested, rebuilt from the {@code products} index and queried natively instead of
 * grouping every matching SKU per request.
 *
 * @param enabled          runs the background projector (default true). New searches require a
 *                         ready projection; off returns retryable 503, never SKU grouping (ADR-029)
 * @param pollInterval     how often the projector looks for SKU documents written since its cursor
 * @param lookback         how far behind its cursor each poll starts again, to cover the refresh
 *                         interval and clock skew between replicas; a rebuild is idempotent
 * @param scanPageSize     SKU documents read per request when scanning or rebuilding
 * @param rebuildBatch     Products rebuilt per bulk request
 * @param readinessRefresh how long a replica trusts its last check that the projection mirrors
 *                         the current {@code products} generation
 * @param leaseDuration    lease of the single active projector; renewed on every poll
 */
@ConfigurationProperties(prefix = "search.product-index")
public record ProductIndexProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("10s") Duration lookback,
        @DefaultValue("2000") int scanPageSize,
        @DefaultValue("500") int rebuildBatch,
        @DefaultValue("5s") Duration readinessRefresh,
        @DefaultValue("30s") Duration leaseDuration) {

    public ProductIndexProperties {
        if (scanPageSize < 1 || rebuildBatch < 1) {
            throw new IllegalArgumentException("search.product-index page and batch sizes must be positive");
        }
    }
}
