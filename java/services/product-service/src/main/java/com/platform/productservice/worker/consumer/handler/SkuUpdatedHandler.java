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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles SKU_UPDATED events from Kafka.
 * Refreshes Redis cache with the full SKU snapshot from the database.
 * Implements staleness guards based on changed fields.
 */
@Component
public class SkuUpdatedHandler {

    private static final Logger log = LoggerFactory.getLogger(SkuUpdatedHandler.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(ServiceConstants.SKU_CACHE_TTL_SECONDS);

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final EventIdempotencyGuard idempotencyGuard;
    private final StalenessChecker stalenessChecker;

    public SkuUpdatedHandler(
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

    @SuppressWarnings("unchecked")
    public void handle(CloudEvent event) {
        String eventId = event.getEventId();
        log.info("SkuUpdatedHandler: processing eventId={}", eventId);

        if (!idempotencyGuard.isNew(eventId)) {
            log.info("SkuUpdatedHandler: duplicate event skipped, eventId={}", eventId);
            return;
        }

        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            log.warn("SkuUpdatedHandler: null payload, eventId={}", eventId);
            return;
        }

        String skuId = extractSkuId(payload);
        if (skuId == null) {
            log.warn("SkuUpdatedHandler: missing skuId in payload, eventId={}", eventId);
            return;
        }

        Long eventUpdatedAt = parseUpdatedAt(payload);
        List<String> changedFields = extractChangedFields(payload);

        Optional<SkuCacheEntry> cached = skuCacheRepository.findById(skuId);

        if (!stalenessChecker.isSkuUpdatedFresh(eventUpdatedAt, cached.orElse(null), changedFields)) {
            log.info("SkuUpdatedHandler: event is stale, skipping cache refresh, skuId={}, eventId={}, changedFields={}",
                    skuId, eventId, changedFields);
            return;
        }

        Optional<Sku> skuOpt = skuRepository.findByIdAndNotDeleted(SkuId.of(skuId));
        if (skuOpt.isEmpty()) {
            log.warn("SkuUpdatedHandler: SKU not found or deleted, skuId={}, eventId={}", skuId, eventId);
            return;
        }

        Sku sku = skuOpt.get();
        Product product = null;
        if (sku.getProductId() != null) {
            product = productRepository.findById(sku.getProductId()).orElse(null);
        }

        SkuCacheEntry entry = SkuCacheEntry.fromDomain(product, sku);
        skuCacheRepository.save(skuId, entry, CACHE_TTL);

        log.info("SkuUpdatedHandler: cache refreshed, skuId={}, eventId={}, changedFields={}, hasProduct={}, cacheTtl={}s",
                skuId, eventId, changedFields, product != null, CACHE_TTL.getSeconds());
    }

    @SuppressWarnings("unchecked")
    private String extractSkuId(Map<String, Object> payload) {
        Object skuIdObj = payload.get("skuId");
        if (skuIdObj == null) {
            skuIdObj = payload.get("id");
        }
        return skuIdObj != null ? String.valueOf(skuIdObj) : null;
    }

    @SuppressWarnings("unchecked")
    private Long parseUpdatedAt(Map<String, Object> payload) {
        Object updatedAtObj = payload.get("updatedAt");
        if (updatedAtObj == null) {
            updatedAtObj = payload.get("updated_at");
        }
        if (updatedAtObj == null) {
            return 0L;
        }

        String updatedAtStr = String.valueOf(updatedAtObj);
        try {
            return Instant.parse(updatedAtStr).toEpochMilli();
        } catch (Exception e) {
            try {
                return Long.parseLong(updatedAtStr);
            } catch (Exception ex) {
                log.warn("SkuUpdatedHandler: could not parse updatedAt '{}', defaulting to 0", updatedAtStr);
                return 0L;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractChangedFields(Map<String, Object> payload) {
        Object changedFieldsObj = payload.get("changedFields");
        if (changedFieldsObj == null) {
            changedFieldsObj = payload.get("changed_fields");
        }
        if (changedFieldsObj instanceof List<?>) {
            return ((List<?>) changedFieldsObj).stream()
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }
}
