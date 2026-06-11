package com.platform.productservice.service;

import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.SkuUpdatedEvent;
import com.platform.productservice.domain.statemachine.FsmTriggerCalculator;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.infrastructure.redis.cache.model.PendingSyncItem;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.outbound.cache.InventorySyncRepository;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.platform.productservice.common.constants.ServiceConstants.SKU_CACHE_TTL_SECONDS;

/**
 * Orchestrates Phase 2 inventory sync operations.
 * Drains items from the Redis pending sync ZSET, applies inventory updates
 * to the database in transactions, triggers FSM auto-hide/reactivate,
 * and acknowledges processed items.
 */
@Service
public class InventorySyncOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncOrchestrator.class);

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final OutboxRepository outboxRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final InventorySyncRepository inventorySyncRepository;
    private final ProductFsmFacade productFsmFacade;
    private final FsmTriggerCalculator fsmTriggerCalculator;
    private final ObjectMapper objectMapper;

    public InventorySyncOrchestrator(
            SkuRepository skuRepository,
            ProductRepository productRepository,
            OutboxRepository outboxRepository,
            SkuCacheRepository skuCacheRepository,
            InventorySyncRepository inventorySyncRepository,
            ProductFsmFacade productFsmFacade,
            FsmTriggerCalculator fsmTriggerCalculator,
            ObjectMapper objectMapper) {
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
        this.outboxRepository = outboxRepository;
        this.skuCacheRepository = skuCacheRepository;
        this.inventorySyncRepository = inventorySyncRepository;
        this.productFsmFacade = productFsmFacade;
        this.fsmTriggerCalculator = fsmTriggerCalculator;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes a batch of inventory sync items grouped by product ID.
     * Each product is processed in its own transaction with pessimistic locking.
     *
     * @param itemsByProductId items grouped by product ID
     * @return list of ZSET member strings that were successfully processed
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<String> syncBatch(Map<String, List<PendingSyncItem>> itemsByProductId) {
        List<String> acknowledgedMembers = new ArrayList<>();

        for (Map.Entry<String, List<PendingSyncItem>> entry : itemsByProductId.entrySet()) {
            String productId = entry.getKey();
            List<PendingSyncItem> items = entry.getValue();

            try {
                processProductItems(productId, items, acknowledgedMembers);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                log.warn("InventorySyncOrchestrator: optimistic lock conflict for product {}, requeuing items",
                        productId);
                inventorySyncRepository.requeuePendingSync(items);
            } catch (Exception e) {
                log.error("InventorySyncOrchestrator: failed to process product {}: {}",
                        productId, e.getMessage(), e);
                inventorySyncRepository.requeuePendingSync(items);
            }
        }

        return acknowledgedMembers;
    }

    private void processProductItems(String productId, List<PendingSyncItem> items,
                                     List<String> acknowledgedMembers) {
        log.debug("InventorySyncOrchestrator: processing {} items for product {}",
                items.size(), productId);

        com.platform.productservice.domain.vo.ProductId pid =
                com.platform.productservice.domain.vo.ProductId.of(productId);

        Optional<Product> optProduct = productRepository.findByIdForNoKeyUpdate(pid);
        if (optProduct.isEmpty()) {
            log.warn("InventorySyncOrchestrator: product {} not found, skipping items", productId);
            for (PendingSyncItem item : items) {
                acknowledgedMembers.add(item.getMemberString());
            }
            inventorySyncRepository.acknowledgePendingSync(
                    items.stream().map(PendingSyncItem::getMemberString).toList());
            return;
        }

        Product product = optProduct.get();

        Map<String, PendingSyncItem> itemsBySkuId = items.stream()
                .collect(Collectors.toMap(PendingSyncItem::getSkuId, item -> item, (a, b) -> b));

        List<Sku> allSkus = skuRepository.findByProductId(pid);

        for (Sku sku : allSkus) {
            PendingSyncItem item = itemsBySkuId.get(sku.getId().getValue());
            if (item == null) continue;

            if (item.getEventTimestamp() > sku.getLastInventorySyncAt()) {
                sku.updateDisplayQuantity(item.getAvailableQuantity(), item.getEventTimestamp());
                Sku savedSku = skuRepository.save(sku);

                log.debug("InventorySyncOrchestrator: updated sku {} quantity to {}",
                        sku.getId().getValue(), item.getAvailableQuantity());

                warmCacheForSku(product, savedSku);

                publishSkuUpdatedEvent(product, savedSku, "INVENTORY_SYNC_PHASE2");
            } else {
                log.debug("InventorySyncOrchestrator: skipping stale update for sku {} (ts {} <= lastSync {})",
                        sku.getId().getValue(), item.getEventTimestamp(), sku.getLastInventorySyncAt());
            }
        }

        List<Sku> updatedSkus = skuRepository.findByProductId(pid);
        Optional<ProductEvent> triggerOpt = fsmTriggerCalculator.calculateFsmTrigger(product, updatedSkus);

        if (triggerOpt.isPresent()) {
            ProductEvent trigger = triggerOpt.get();
            log.info("InventorySyncOrchestrator: triggering FSM {} for product {}",
                    trigger, productId);

            switch (trigger) {
                case AUTO_HIDE_OUT_OF_STOCK -> productFsmFacade.autoHideOutOfStock(product);
                case AUTO_REACTIVATE_IN_STOCK -> productFsmFacade.autoReactivateInStock(product);
                default -> log.warn("InventorySyncOrchestrator: unhandled trigger {} for product {}",
                        trigger, productId);
            }
        }

        List<String> members = items.stream().map(PendingSyncItem::getMemberString).toList();
        inventorySyncRepository.acknowledgePendingSync(members);
        acknowledgedMembers.addAll(members);

        log.info("InventorySyncOrchestrator: completed batch for product {}, processed {} items",
                productId, items.size());
    }

    private void warmCacheForSku(Product product, Sku sku) {
        try {
            SkuCacheEntry entry = SkuCacheEntry.fromDomain(product, sku);
            skuCacheRepository.save(sku.getId().getValue(), entry,
                    Duration.ofSeconds(SKU_CACHE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("InventorySyncOrchestrator: failed to warm cache for sku {}: {}",
                    sku.getId().getValue(), e.getMessage());
        }
    }

    private void publishSkuUpdatedEvent(Product product, Sku sku, String changeSource) {
        try {
            SkuUpdatedEvent domainEvent = new SkuUpdatedEvent(
                    sku,
                    product.getName(),
                    product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                    product.getStatus().name(),
                    List.of("displayQuantity"),
                    changeSource
            );

            String payload = objectMapper.writeValueAsString(domainEvent.toCloudEventPayload());
            OutboxEvent outboxEvent = OutboxEvent.of(
                    domainEvent,
                    sku.getId().getValue(),
                    sku.getId().getValue(),
                    payload
            );
            outboxRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("InventorySyncOrchestrator: failed to publish SKU updated event for sku {}: {}",
                    sku.getId().getValue(), e.getMessage(), e);
        }
    }
}
