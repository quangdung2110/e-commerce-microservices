package com.platform.productservice.worker.consumer.handler;

import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import com.platform.productservice.worker.common.EventIdempotencyGuard;
import com.platform.productservice.worker.common.StalenessChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Handles SKU_CREATED events from Kafka.
 * Refreshes Redis cache with the full SKU snapshot from the database.
 */
@Component
public class SkuCreatedHandler {

    private static final Logger log = LoggerFactory.getLogger(SkuCreatedHandler.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(ServiceConstants.SKU_CACHE_TTL_SECONDS);

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final EventIdempotencyGuard idempotencyGuard;
    private final StalenessChecker stalenessChecker;

    public SkuCreatedHandler(
            SkuRepository skuRepository,
            ProductRepository productRepository,
            SkuCacheRepository skuCacheRepository,
            EventIdempotencyGuard idempotencyGuard,
            StalenessChecker stalenessChecker) {
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
        this.skuCacheRepository = skuCacheRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.stalenessChecker = stalenessChecker;
    }

    public void handle(CloudEvent event) {
        String eventId = event.getEventId();
        log.info("SkuCreatedHandler: processing eventId={}", eventId);

        if (!idempotencyGuard.isNew(eventId)) {
            log.info("SkuCreatedHandler: duplicate event skipped, eventId={}", eventId);
            return;
        }

        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            log.warn("SkuCreatedHandler: null payload, eventId={}", eventId);
            return;
        }

        String skuId = extractSkuId(payload);
        if (skuId == null) {
            log.warn("SkuCreatedHandler: missing skuId in payload, eventId={}", eventId);
            return;
        }

        Long eventCreatedAt = parseTimestamp(event.getTimestamp());
        Optional<SkuCacheEntry> cached = skuCacheRepository.findById(skuId);

        if (!stalenessChecker.isSkuCreatedFresh(eventCreatedAt, cached.orElse(null))) {
            log.info("SkuCreatedHandler: event is stale, skipping cache refresh, skuId={}, eventId={}",
                    skuId, eventId);
            return;
        }

        Optional<Sku> skuOpt = skuRepository.findByIdAndNotDeleted(SkuId.of(skuId));
        if (skuOpt.isEmpty()) {
            log.warn("SkuCreatedHandler: SKU not found or deleted, skuId={}, eventId={}", skuId, eventId);
            return;
        }

        Sku sku = skuOpt.get();
        Product product = null;
        if (sku.getProductId() != null) {
            product = productRepository.findById(sku.getProductId()).orElse(null);
        }

        SkuCacheEntry entry = SkuCacheEntry.fromDomain(product, sku);
        skuCacheRepository.save(skuId, entry, CACHE_TTL);

        log.info("SkuCreatedHandler: cache refreshed, skuId={}, eventId={}, hasProduct={}, cacheTtl={}s",
                skuId, eventId, product != null, CACHE_TTL.getSeconds());
    }

    private String extractSkuId(Map<String, Object> payload) {
        Object skuIdObj = payload.get("skuId");
        if (skuIdObj == null) {
            skuIdObj = payload.get("id");
        }
        return skuIdObj != null ? String.valueOf(skuIdObj) : null;
    }

    private Long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            try {
                return Long.parseLong(timestamp);
            } catch (Exception ex) {
                log.warn("SkuCreatedHandler: could not parse timestamp '{}', defaulting to 0", timestamp);
                return 0L;
            }
        }
    }
}
