package com.ecommerce.search.service;

import com.ecommerce.search.config.ProductIndexProperties;
import com.ecommerce.search.repository.es.ProductCardIndex;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Whether {@code GET /api/v2/search/products} may read {@code product_cards} (ADR-027): only when
 * the projection is enabled and mirrors the {@code products} generation the SKU alias serves now.
 * Otherwise — first start, a full reindex just moved the alias, Elasticsearch unreachable — the
 * new request fails with a retryable 503 (ADR-029). Legacy cursors keep their own PIT.
 *
 * <p>The answer is cached for {@code search.product-index.readiness-refresh}, so a request pays at
 * most one metadata lookup per interval and replica.
 */
@Component
public class ProductCardReadiness {

    private static final Logger log = LoggerFactory.getLogger(ProductCardReadiness.class);

    private final ProductCardIndex index;
    private final ProductIndexProperties properties;
    private volatile boolean ready;
    private volatile long checkedAtNanos;
    private volatile boolean checkedOnce;

    public ProductCardReadiness(ProductCardIndex index, ProductIndexProperties properties, MeterRegistry meters) {
        this.index = index;
        this.properties = properties;
        Gauge.builder("search.product_cards.ready", this, readiness -> readiness.ready ? 1 : 0)
                .description("1 when product searches read the product_cards projection, 0 when they group SKUs")
                .register(meters);
    }

    public boolean ready() {
        if (!properties.enabled()) {
            return false;
        }
        long now = System.nanoTime();
        if (!checkedOnce || now - checkedAtNanos >= properties.readinessRefresh().toNanos()) {
            synchronized (this) {
                if (!checkedOnce || now - checkedAtNanos >= properties.readinessRefresh().toNanos()) {
                    boolean mirrors;
                    try {
                        mirrors = index.mirrorsCurrentSource();
                    } catch (IOException | RuntimeException e) {
                        mirrors = false;
                    }
                    if (mirrors != ready || !checkedOnce) {
                        log.info("product searches are {}", mirrors ? "ready on product_cards" : "temporarily unavailable");
                    }
                    ready = mirrors;
                    checkedAtNanos = now;
                    checkedOnce = true;
                }
            }
        }
        return ready;
    }
}
