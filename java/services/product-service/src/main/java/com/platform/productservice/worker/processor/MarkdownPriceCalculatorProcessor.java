package com.platform.productservice.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.enums.DiscountType;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.SkuUpdatedEvent;
import com.platform.productservice.domain.service.MarkdownCalculator;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.redis.cache.model.MarkdownCacheEntry;
import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import com.platform.productservice.port.outbound.cache.MarkdownCacheRepository;
import com.platform.productservice.port.outbound.database.MarkdownRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import com.platform.productservice.service.OutboxService;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processor that handles markdown price calculation for MARKDOWN_CREATED,
 * MARKDOWN_UPDATED, and MARKDOWN_ENDED events.
 *
 * <p>Uses distributed locking per product to prevent concurrent recalculation.
 * Lock order: Product (FOR NO KEY UPDATE) before SKU (FOR UPDATE).</p>
 */
@Component
public class MarkdownPriceCalculatorProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarkdownPriceCalculatorProcessor.class);
    private static final String SCOPE_TYPE_CATEGORY = "CATEGORY";
    private static final String SCOPE_TYPE_PRODUCT = "PRODUCT";
    private static final Duration DISTRIBUTED_LOCK_TTL = Duration.ofSeconds(30);

    private final MarkdownRepository markdownRepository;
    private final DistributedLockRepository distributedLockRepository;
    private final MarkdownCacheRepository markdownCacheRepository;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final MarkdownCalculator markdownCalculator;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public MarkdownPriceCalculatorProcessor(
            MarkdownRepository markdownRepository,
            DistributedLockRepository distributedLockRepository,
            MarkdownCacheRepository markdownCacheRepository,
            ProductRepository productRepository,
            SkuRepository skuRepository,
            MarkdownCalculator markdownCalculator,
            OutboxService outboxService,
            ObjectMapper objectMapper) {
        this.markdownRepository = markdownRepository;
        this.distributedLockRepository = distributedLockRepository;
        this.markdownCacheRepository = markdownCacheRepository;
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.markdownCalculator = markdownCalculator;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes a MARKDOWN_CREATED event.
     * Applies markdown discounts to all affected SKUs.
     */
    public void processCreated(CloudEvent event) {
        Map<String, Object> payload = event.getPayload();
        String ruleId = (String) payload.get("rule_id");
        String scopeType = (String) payload.get("scope_type");
        String scopeId = (String) payload.get("scope_id");
        String discountTypeStr = (String) payload.get("discount_type");
        BigDecimal discountValue = parseBigDecimal(payload.get("discount_value"));
        BigDecimal maxDiscountAmount = parseBigDecimal(payload.get("max_discount_amount"));
        String expiresAtStr = (String) payload.get("expires_at");

        log.info("MarkdownPriceCalculatorProcessor.processCreated: ruleId={}, scopeType={}, scopeId={}",
                ruleId, scopeType, scopeId);

        List<String> skuIds = resolveSkuIdsByScope(scopeType, scopeId);
        if (skuIds.isEmpty()) {
            log.info("MarkdownPriceCalculatorProcessor.processCreated: no SKUs found for scope, ruleId={}",
                    ruleId);
            return;
        }

        Map<String, List<String>> skusByProductId = groupSkuIdsByProductId(skuIds);

        for (Map.Entry<String, List<String>> entry : skusByProductId.entrySet()) {
            String productId = entry.getKey();
            List<String> productSkuIds = entry.getValue();
            processCreatedForProduct(ruleId, productId, productSkuIds, discountTypeStr,
                    discountValue, maxDiscountAmount, expiresAtStr);
        }

        log.info("MarkdownPriceCalculatorProcessor.processCreated: completed, ruleId={}, affectedProducts={}",
                ruleId, skusByProductId.size());
    }

    /**
     * Processes a MARKDOWN_UPDATED event.
     * Recalculates markdown prices for all SKUs affected by the rule.
     */
    public void processUpdated(CloudEvent event) {
        Map<String, Object> payload = event.getPayload();
        String ruleId = (String) payload.get("rule_id");
        String discountTypeStr = (String) payload.get("discount_type");
        BigDecimal discountValue = parseBigDecimal(payload.get("discount_value"));
        BigDecimal maxDiscountAmount = parseBigDecimal(payload.get("max_discount_amount"));
        String expiresAtStr = (String) payload.get("expires_at");

        log.info("MarkdownPriceCalculatorProcessor.processUpdated: ruleId={}", ruleId);

        List<MarkdownApplied> existingMarkdowns = markdownRepository.findByRuleId(ruleId);
        if (existingMarkdowns.isEmpty()) {
            log.info("MarkdownPriceCalculatorProcessor.processUpdated: no existing markdowns for ruleId={}",
                    ruleId);
            return;
        }

        Map<String, List<MarkdownApplied>> markdownsByProduct = existingMarkdowns.stream()
                .collect(Collectors.groupingBy(m -> m.getProductId().getValue()));

        for (Map.Entry<String, List<MarkdownApplied>> entry : markdownsByProduct.entrySet()) {
            String productId = entry.getKey();
            List<String> productSkuIds = entry.getValue().stream()
                    .map(m -> m.getSkuId().getValue())
                    .collect(Collectors.toList());

            processCreatedForProduct(ruleId, productId, productSkuIds, discountTypeStr,
                    discountValue, maxDiscountAmount, expiresAtStr);
        }

        log.info("MarkdownPriceCalculatorProcessor.processUpdated: completed, ruleId={}, affectedProducts={}",
                ruleId, markdownsByProduct.size());
    }

    /**
     * Processes a MARKDOWN_ENDED event.
     * Resets promotional prices to NULL and removes markdown records.
     */
    public void processEnded(CloudEvent event) {
        Map<String, Object> payload = event.getPayload();
        String ruleId = (String) payload.get("rule_id");

        log.info("MarkdownPriceCalculatorProcessor.processEnded: ruleId={}", ruleId);

        List<MarkdownApplied> markdowns = markdownRepository.findByRuleId(ruleId);
        if (markdowns.isEmpty()) {
            log.info("MarkdownPriceCalculatorProcessor.processEnded: no markdown found for ruleId={} — idempotent skip",
                    ruleId);
            return;
        }

        Map<String, List<MarkdownApplied>> markdownsByProductId = markdowns.stream()
                .collect(Collectors.groupingBy(m -> m.getProductId().getValue()));

        Set<String> deletedSkuIds = new HashSet<>();
        for (Map.Entry<String, List<MarkdownApplied>> entry : markdownsByProductId.entrySet()) {
            String productId = entry.getKey();
            List<MarkdownApplied> productMarkdowns = entry.getValue();

            String lockKey = "markdown:" + productId;
            boolean lockAcquired = distributedLockRepository.acquireLock(lockKey, DISTRIBUTED_LOCK_TTL);
            if (!lockAcquired) {
                log.warn("MarkdownPriceCalculatorProcessor.processEnded: could not acquire lock for productId={}",
                        productId);
                continue;
            }

            try {
                processEndedForProduct(ruleId, productId, productMarkdowns);
                deletedSkuIds.addAll(productMarkdowns.stream()
                        .map(m -> m.getSkuId().getValue())
                        .collect(Collectors.toList()));
            } finally {
                distributedLockRepository.releaseLock(lockKey);
            }
        }

        if (!deletedSkuIds.isEmpty()) {
            markdownCacheRepository.deleteBySkuIds(new ArrayList<>(deletedSkuIds));
        }

        log.info("MarkdownPriceCalculatorProcessor.processEnded: completed, ruleId={}, affectedSkus={}",
                ruleId, deletedSkuIds.size());
    }

    private void processCreatedForProduct(
            String ruleId,
            String productId,
            List<String> skuIds,
            String discountTypeStr,
            BigDecimal discountValue,
            BigDecimal maxDiscountAmount,
            String expiresAtStr) {

        String lockKey = "markdown:" + productId;
        boolean lockAcquired = distributedLockRepository.acquireLock(lockKey, DISTRIBUTED_LOCK_TTL);
        if (!lockAcquired) {
            log.warn("MarkdownPriceCalculatorProcessor: could not acquire lock for productId={}, retrying",
                    productId);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            lockAcquired = distributedLockRepository.acquireLock(lockKey, DISTRIBUTED_LOCK_TTL);
            if (!lockAcquired) {
                log.error("MarkdownPriceCalculatorProcessor: failed to acquire lock for productId={} after retry",
                        productId);
                throw new RuntimeException("Failed to acquire distributed lock for product: " + productId);
            }
        }

        try {
            doProcessCreatedForProduct(ruleId, productId, skuIds, discountTypeStr,
                    discountValue, maxDiscountAmount, expiresAtStr);
        } finally {
            distributedLockRepository.releaseLock(lockKey);
        }
    }

    private void doProcessCreatedForProduct(
            String ruleId,
            String productId,
            List<String> skuIds,
            String discountTypeStr,
            BigDecimal discountValue,
            BigDecimal maxDiscountAmount,
            String expiresAtStr) {

        ProductId pid = ProductId.of(productId);
        Product product = productRepository.findByIdForNoKeyUpdate(pid)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        DiscountType discountType = DiscountType.valueOf(discountTypeStr);
        LocalDateTime expiresAt = expiresAtStr != null
                ? LocalDateTime.parse(expiresAtStr, DateTimeFormatter.ISO_DATE_TIME)
                : null;

        List<Sku> skus = new ArrayList<>();
        for (String skuId : skuIds) {
            SkuId sid = SkuId.of(skuId);
            skuRepository.findByIdForUpdate(sid).ifPresent(skus::add);
        }

        List<DomainEvent> outboxEvents = new ArrayList<>();
        List<MarkdownCacheEntry> cacheEntries = new ArrayList<>();

        for (Sku sku : skus) {
            if (sku.getOriginalPrice() == null) {
                log.debug("MarkdownPriceCalculatorProcessor: skipping SKU {} with null originalPrice",
                        sku.getId().getValue());
                continue;
            }

            BigDecimal calculatedPrice = markdownCalculator.calculate(
                    sku.getOriginalPrice(),
                    discountType,
                    discountValue,
                    maxDiscountAmount
            );

            sku.setPromotionalPrice(calculatedPrice);
            sku.setUpdatedAt(LocalDateTime.now());
            skuRepository.save(sku);

            Optional<MarkdownApplied> existingOpt = markdownRepository.findByRuleIdAndSkuId(ruleId, sku.getId().getValue());
            MarkdownApplied markdown = existingOpt.orElseGet(() -> {
                MarkdownApplied m = new MarkdownApplied();
                m.setId(UUID.randomUUID().toString());
                m.setRuleId(ruleId);
                m.setProductId(pid);
                m.setSkuId(sku.getId());
                m.setDiscountType(discountType);
                m.setDiscountValue(discountValue);
                m.setMaxDiscountAmount(maxDiscountAmount);
                m.setAppliedAt(LocalDateTime.now());
                m.setExpiresAt(expiresAt);
                return m;
            });
            markdown.setCalculatedPrice(calculatedPrice);
            markdownRepository.save(markdown);

            String productName = product.getName();
            String categoryId = product.getCategoryId() != null ? product.getCategoryId().getValue() : null;
            String productStatus = product.getStatus().name();

            outboxEvents.add(new SkuUpdatedEvent(
                    sku,
                    productName,
                    categoryId,
                    productStatus,
                    List.of("promotionalPrice"),
                    "MARKDOWN_CREATED"
            ));

            cacheEntries.add(MarkdownCacheEntry.builder()
                    .ruleId(ruleId)
                    .discountType(discountType.name())
                    .discountValue(discountValue)
                    .calculatedPrice(calculatedPrice)
                    .expiresAt(expiresAtStr)
                    .build());
        }

        if (!outboxEvents.isEmpty()) {
            saveOutboxEvents(outboxEvents);
        }

        for (int i = 0; i < cacheEntries.size(); i++) {
            String skuId = skuIds.get(i);
            MarkdownCacheEntry entry = cacheEntries.get(i);
            long ttlSeconds = calculateTtlSeconds(expiresAt);
            markdownCacheRepository.save(skuId, entry, Duration.ofSeconds(ttlSeconds));
        }

        log.info("MarkdownPriceCalculatorProcessor.doProcessCreatedForProduct: ruleId={}, productId={}, skusUpdated={}",
                ruleId, productId, skus.size());
    }

    private void processEndedForProduct(
            String ruleId,
            String productId,
            List<MarkdownApplied> markdowns) {

        ProductId pid = ProductId.of(productId);
        Product product = productRepository.findById(pid).orElse(null);
        String productName = product != null ? product.getName() : null;
        String categoryId = product != null && product.getCategoryId() != null
                ? product.getCategoryId().getValue() : null;
        String productStatus = product != null ? product.getStatus().name() : null;

        List<DomainEvent> outboxEvents = new ArrayList<>();

        for (MarkdownApplied markdown : markdowns) {
            SkuId sid = markdown.getSkuId();
            Optional<Sku> skuOpt = skuRepository.findByIdForUpdate(sid);
            if (skuOpt.isPresent()) {
                Sku sku = skuOpt.get();
                sku.removeMarkdown();
                sku.setUpdatedAt(LocalDateTime.now());
                skuRepository.save(sku);

                outboxEvents.add(new SkuUpdatedEvent(
                        sku,
                        productName,
                        categoryId,
                        productStatus,
                        List.of("promotionalPrice"),
                        "MARKDOWN_ENDED"
                ));
            }
        }

        if (!outboxEvents.isEmpty()) {
            saveOutboxEvents(outboxEvents);
        }

        markdownRepository.deleteByRuleId(ruleId);

        log.info("MarkdownPriceCalculatorProcessor.processEndedForProduct: ruleId={}, productId={}, skusReset={}",
                ruleId, productId, outboxEvents.size());
    }

    private List<String> resolveSkuIdsByScope(String scopeType, String scopeId) {
        if (SCOPE_TYPE_CATEGORY.equalsIgnoreCase(scopeType)) {
            List<Product> products = productRepository.findByCategoryIdAndStatusIn(scopeId, List.of("ACTIVE", "HIDDEN"));
            List<String> skuIds = new ArrayList<>();
            for (Product product : products) {
                List<Sku> skus = skuRepository.findByProductId(product.getId());
                for (Sku sku : skus) {
                    if (!sku.isDeleted()) {
                        skuIds.add(sku.getId().getValue());
                    }
                }
            }
            return skuIds;
        } else if (SCOPE_TYPE_PRODUCT.equalsIgnoreCase(scopeType)) {
            List<Sku> skus = skuRepository.findByProductId(ProductId.of(scopeId));
            return skus.stream()
                    .filter(sku -> !sku.isDeleted())
                    .map(sku -> sku.getId().getValue())
                    .collect(Collectors.toList());
        } else {
            log.warn("MarkdownPriceCalculatorProcessor: unknown scopeType={}, scopeId={}", scopeType, scopeId);
            return List.of();
        }
    }

    private Map<String, List<String>> groupSkuIdsByProductId(List<String> skuIds) {
        Map<String, List<String>> result = new HashMap<>();
        for (String skuId : skuIds) {
            SkuId sid = SkuId.of(skuId);
            Optional<Sku> skuOpt = skuRepository.findById(sid);
            if (skuOpt.isPresent()) {
                String pid = skuOpt.get().getProductId().getValue();
                result.computeIfAbsent(pid, k -> new ArrayList<>()).add(skuId);
            }
        }
        return result;
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private long calculateTtlSeconds(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            return 86400L;
        }
        long expiryEpoch = expiresAt.toEpochSecond(ZoneOffset.UTC);
        long nowEpoch = System.currentTimeMillis() / 1000;
        long ttl = expiryEpoch - nowEpoch + 300;
        return Math.max(ttl, 0);
    }

    private void saveOutboxEvents(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) return;
        try {
            List<String> aggregateIds = events.stream().map(DomainEvent::getAggregateId).collect(Collectors.toList());
            List<String> partitionKeys = events.stream().map(DomainEvent::getPartitionKey).collect(Collectors.toList());
            outboxService.saveEvents(events, aggregateIds, partitionKeys);
        } catch (Exception e) {
            log.error("MarkdownPriceCalculatorProcessor: failed to save outbox events: {}", e.getMessage(), e);
        }
    }
}
