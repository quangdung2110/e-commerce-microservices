package com.platform.productservice.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.infrastructure.redis.RedisKeyBuilder;
import com.platform.productservice.infrastructure.redis.cache.model.PendingSyncItem;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.infrastructure.redis.sync.InventoryFastSyncLuaExecutor;
import com.platform.productservice.infrastructure.redis.sync.InventoryFastSyncLuaExecutor.LuaSyncResult;
import com.platform.productservice.port.outbound.cache.InventorySyncRepository;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import com.platform.productservice.worker.scheduler.InventorySyncMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Phase 1: Fast Path Kafka Consumer for inventory sync.
 *
 * <p>This consumer processes INVENTORY_QUANTITY_CHANGED events from Inventory Service
 * with zero database locks and sub-5ms latency. The flow:</p>
 * <ol>
 *   <li>Parse Kafka message into InventoryEvent</li>
 *   <li>Read existing cache entry and merge inventory fields</li>
 *   <li>Execute Lua CAS on Redis (staleness guard + atomic update)</li>
 *   <li>ZADD to pending queue for Phase 2</li>
 *   <li>ACK Kafka offset</li>
 * </ol>
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li>Manual ACK only after Lua script succeeds (regardless of applied/stale/cacheMiss)</li>
 *   <li>Exception: do NOT ACK (Kafka redelivers for retry)</li>
 *   <li>No database access in this phase</li>
 *   <li>Idempotent via Lua CAS (same event re-applied is a no-op)</li>
 *   <li>Cache is preserved: inventory fields are merged, non-inventory fields kept intact</li>
 * </ul>
 */
@Service
public class InventorySyncFastPathConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncFastPathConsumer.class);

    private static final String EVENT_TYPE_INVENTORY_QUANTITY_CHANGED = "INVENTORY_QUANTITY_CHANGED";

    private final ObjectMapper objectMapper;
    private final InventoryFastSyncLuaExecutor luaExecutor;
    private final InventorySyncRepository inventorySyncRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final RedisKeyBuilder redisKeyBuilder;
    private final InventorySyncMetrics metrics;

    public InventorySyncFastPathConsumer(
            ObjectMapper objectMapper,
            InventoryFastSyncLuaExecutor luaExecutor,
            InventorySyncRepository inventorySyncRepository,
            SkuCacheRepository skuCacheRepository,
            RedisKeyBuilder redisKeyBuilder,
            InventorySyncMetrics metrics) {
        this.objectMapper = objectMapper;
        this.luaExecutor = luaExecutor;
        this.inventorySyncRepository = inventorySyncRepository;
        this.skuCacheRepository = skuCacheRepository;
        this.redisKeyBuilder = redisKeyBuilder;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = ServiceConstants.TOPIC_INVENTORY_EVENTS,
            groupId = ServiceConstants.CG_INVENTORY_SYNC,
            containerFactory = "inventorySyncListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("InventorySyncFastPathConsumer: received record [topic={}, partition={}, offset={}]",
                record.topic(), record.partition(), record.offset());

        InventoryEvent event;
        try {
            event = objectMapper.readValue(record.value(), InventoryEvent.class);
        } catch (Exception e) {
            log.error("InventorySyncFastPathConsumer: failed to deserialize inventory event " +
                            "[topic={}, partition={}, offset={}]: {}",
                    record.topic(), record.partition(), record.offset(), e.getMessage(), e);
            ack.acknowledge();
            return;
        }

        if (!EVENT_TYPE_INVENTORY_QUANTITY_CHANGED.equals(event.getEventType())) {
            log.debug("InventorySyncFastPathConsumer: skipping non-inventory event type={}, eventId={}",
                    event.getEventType(), event.getEventId());
            ack.acknowledge();
            return;
        }

        if (!event.isValid()) {
            log.warn("InventorySyncFastPathConsumer: invalid inventory event [eventId={}, skuId={}, productId={}]",
                    event.getEventId(), event.getSkuId(), event.getProductId());
            ack.acknowledge();
            return;
        }

        try {
            processInventoryEvent(event);
            ack.acknowledge();
            log.debug("InventorySyncFastPathConsumer: offset committed [partition={}, offset={}, eventId={}]",
                    record.partition(), record.offset(), event.getEventId());
        } catch (Exception e) {
            log.error("InventorySyncFastPathConsumer: failed to process inventory event " +
                            "[partition={}, offset={}, skuId={}, eventId={}]: {}",
                    record.partition(), record.offset(), event.getSkuId(), event.getEventId(),
                    e.getMessage(), e);
            throw e;
        }
    }

    private void processInventoryEvent(InventoryEvent event) {
        String skuId = event.getSkuId();
        String productId = event.getProductId();
        long eventTimestamp = event.getEventTimestamp();
        int availableQuantity = event.getAvailableQuantity();
        String skuStatus = event.deriveSkuStatus();

        String skuJson = buildMergedSkuJson(skuId, productId, availableQuantity, eventTimestamp, skuStatus);

        LuaSyncResult result = luaExecutor.execute(
                skuId,
                eventTimestamp,
                availableQuantity,
                productId,
                skuStatus,
                skuJson
        );

        if (!result.success) {
            log.error("InventorySyncFastPathConsumer: Lua execution failed for skuId={}, ts={}, error={}",
                    skuId, eventTimestamp, result.message);
            metrics.recordError();
            throw new IllegalStateException("Lua execution failed: " + result.message);
        }

        if (result.applied) {
            log.debug("InventorySyncFastPathConsumer: Lua CAS applied for skuId={}, ts={}, qty={}",
                    skuId, eventTimestamp, availableQuantity);
            metrics.recordApplied();
        } else if (result.stale) {
            log.debug("InventorySyncFastPathConsumer: Lua CAS rejected stale event for skuId={}, ts={}",
                    skuId, eventTimestamp);
            metrics.recordStale();
        } else if (result.cacheMiss) {
            log.debug("InventorySyncFastPathConsumer: Lua CAS cache miss for skuId={}, ts={} — ZADD to pending",
                    skuId, eventTimestamp);
            metrics.recordCacheMiss();
        }

        inventorySyncRepository.addPendingSync(
                skuId,
                eventTimestamp,
                availableQuantity,
                skuStatus
        );

        log.debug("InventorySyncFastPathConsumer: added to pending queue " +
                        "[skuId={}, ts={}, qty={}, status={}]",
                skuId, eventTimestamp, availableQuantity, skuStatus);
    }

    private String buildMergedSkuJson(String skuId, String productId,
                                       int availableQuantity, long eventTimestamp, String skuStatus) {
        SkuCacheEntry existingEntry = skuCacheRepository.findById(skuId).orElse(null);

        if (existingEntry != null) {
            SkuCacheEntry merged = SkuCacheEntry.builder()
                    .skuId(skuId)
                    .productId(productId)
                    .skuCode(existingEntry.getSkuCode())
                    .productName(existingEntry.getProductName())
                    .mainImageUrl(existingEntry.getMainImageUrl())
                    .originalPrice(existingEntry.getOriginalPrice())
                    .promotionalPrice(existingEntry.getPromotionalPrice())
                    .bestPrice(existingEntry.getBestPrice())
                    .hasPromotion(existingEntry.isHasPromotion())
                    .displayQuantity(availableQuantity)
                    .lastInventorySyncAt(eventTimestamp)
                    .skuStatus(skuStatus)
                    .productStatus(existingEntry.getProductStatus())
                    .isAvailable(availableQuantity > 0 && "ACTIVE".equals(skuStatus) && !existingEntry.isDeleted())
                    .isDeleted(existingEntry.isDeleted())
                    .variantAttributes(existingEntry.getVariantAttributes())
                    .priceUpdatedAt(existingEntry.getPriceUpdatedAt())
                    .build();
            return merged.toJson();
        }

        SkuCacheEntry minimalEntry = SkuCacheEntry.builder()
                .skuId(skuId)
                .productId(productId)
                .displayQuantity(availableQuantity)
                .lastInventorySyncAt(eventTimestamp)
                .skuStatus(skuStatus)
                .isAvailable(availableQuantity > 0 && "ACTIVE".equals(skuStatus))
                .isDeleted(false)
                .priceUpdatedAt(Instant.ofEpochMilli(eventTimestamp)
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .build();
        return minimalEntry.toJson();
    }
}
