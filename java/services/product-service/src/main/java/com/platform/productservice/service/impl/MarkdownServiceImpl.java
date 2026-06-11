package com.platform.productservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.enums.DiscountType;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.SkuUpdatedEvent;
import com.platform.productservice.domain.exception.ProductNotFoundException;
import com.platform.productservice.domain.exception.SkuNotFoundException;
import com.platform.productservice.domain.service.MarkdownCalculator;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.redis.cache.model.MarkdownCacheEntry;
import com.platform.productservice.port.inbound.MarkdownServicePort.MarkdownApplication;
import com.platform.productservice.port.inbound.MarkdownServicePort;
import com.platform.productservice.port.outbound.cache.DistributedLockRepository;
import com.platform.productservice.port.outbound.cache.MarkdownCacheRepository;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import com.platform.productservice.port.outbound.database.MarkdownRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.platform.productservice.common.constants.ServiceConstants.DISTRIBUTED_LOCK_TTL_SECONDS;
import static com.platform.productservice.common.constants.ServiceConstants.MARKDOWN_CACHE_TTL_BUFFER_SECONDS;

/**
 * Markdown service implementation for applying, removing, and recalculating markdown prices.
 * Uses distributed locking to prevent concurrent markdown operations on the same product.
 */
@Service
@Transactional
public class MarkdownServiceImpl implements MarkdownServicePort {

    private static final Logger log = LoggerFactory.getLogger(MarkdownServiceImpl.class);

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final MarkdownRepository markdownRepository;
    private final OutboxRepository outboxRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final MarkdownCacheRepository markdownCacheRepository;
    private final MarkdownCalculator markdownCalculator;
    private final DistributedLockRepository distributedLockRepository;
    private final ObjectMapper objectMapper;

    public MarkdownServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            MarkdownRepository markdownRepository,
            OutboxRepository outboxRepository,
            SkuCacheRepository skuCacheRepository,
            MarkdownCacheRepository markdownCacheRepository,
            MarkdownCalculator markdownCalculator,
            DistributedLockRepository distributedLockRepository,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.markdownRepository = markdownRepository;
        this.outboxRepository = outboxRepository;
        this.skuCacheRepository = skuCacheRepository;
        this.markdownCacheRepository = markdownCacheRepository;
        this.markdownCalculator = markdownCalculator;
        this.distributedLockRepository = distributedLockRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public MarkdownApplied applyMarkdown(MarkdownApplication application) {
        log.info("MarkdownServiceImpl.applyMarkdown: ruleId={}, skuId={}",
                application.ruleId(), application.skuId());

        String lockKey = "product:lock:markdown:" + application.productId();
        Duration lockTtl = Duration.ofSeconds(DISTRIBUTED_LOCK_TTL_SECONDS);

        try {
            return distributedLockRepository.executeWithLock(lockKey, lockTtl, () -> {
                return doApplyMarkdown(application);
            });
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            log.error("MarkdownServiceImpl.applyMarkdown: failed to acquire lock for product {}: {}",
                    application.productId(), e.getMessage());
            throw new RuntimeException("Failed to acquire lock for markdown operation", e);
        }
    }

    private MarkdownApplied doApplyMarkdown(MarkdownApplication application) {
        ProductId pid = ProductId.of(application.productId());
        SkuId sid = SkuId.of(application.skuId());

        Product product = productRepository.findByIdForNoKeyUpdate(pid)
                .orElseThrow(() -> new ProductNotFoundException(application.productId()));

        Sku sku = skuRepository.findByIdForUpdate(sid)
                .orElseThrow(() -> new SkuNotFoundException(application.skuId()));

        if (application.startTime() != null && application.startTime().isAfter(LocalDateTime.now())) {
            log.info("MarkdownServiceImpl.applyMarkdown: start time in future for ruleId={}, scheduling delayed processing",
                    application.ruleId());
            persistDelayedMarkdown(application);
            return null;
        }

        BigDecimal calculatedPrice = markdownCalculator.calculate(
                sku.getOriginalPrice(),
                application.discountType(),
                application.discountValue(),
                application.maxDiscountAmount()
        );

        sku.setPromotionalPrice(calculatedPrice);
        sku.setUpdatedAt(LocalDateTime.now());
        Sku savedSku = skuRepository.save(sku);

        MarkdownApplied markdown = new MarkdownApplied();
        markdown.setId(UUID.randomUUID().toString());
        markdown.setRuleId(application.ruleId());
        markdown.setProductId(pid);
        markdown.setSkuId(sid);
        markdown.setDiscountType(application.discountType());
        markdown.setDiscountValue(application.discountValue());
        markdown.setMaxDiscountAmount(application.maxDiscountAmount());
        markdown.setCalculatedPrice(calculatedPrice);
        markdown.setAppliedAt(LocalDateTime.now());
        markdown.setExpiresAt(application.expiresAt());
        MarkdownApplied savedMarkdown = markdownRepository.save(markdown);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new SkuUpdatedEvent(
                savedSku,
                product.getName(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                product.getStatus().name(),
                List.of("promotionalPrice"),
                "MARKDOWN_CREATED"
        ));
        saveOutboxEvents(events);

        setMarkdownCache(savedSku.getId().getValue(), savedMarkdown);

        log.info("MarkdownServiceImpl.applyMarkdown: applied markdown ruleId={} to skuId={}, price={}",
                application.ruleId(), application.skuId(), calculatedPrice);
        return savedMarkdown;
    }

    @Override
    public void removeMarkdown(String ruleId) {
        log.info("MarkdownServiceImpl.removeMarkdown: ruleId={}", ruleId);

        List<MarkdownApplied> markdowns = markdownRepository.findByRuleId(ruleId);
        if (markdowns.isEmpty()) {
            log.debug("MarkdownServiceImpl.removeMarkdown: no markdown found for ruleId={}", ruleId);
            return;
        }

        for (MarkdownApplied markdown : markdowns) {
            SkuId sid = markdown.getSkuId();
            Sku sku = skuRepository.findByIdForUpdate(sid).orElse(null);
            if (sku != null) {
                sku.removeMarkdown();
                sku.setUpdatedAt(LocalDateTime.now());
                Sku savedSku = skuRepository.save(sku);

                Product product = productRepository.findById(markdown.getProductId()).orElse(null);
                if (product != null) {
                    List<DomainEvent> events = new ArrayList<>();
                    events.add(new SkuUpdatedEvent(
                            savedSku,
                            product.getName(),
                            product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                            product.getStatus().name(),
                            List.of("promotionalPrice"),
                            "MARKDOWN_REMOVED"
                    ));
                    saveOutboxEvents(events);
                }
            }

            markdownCacheRepository.delete(sku.getId().getValue());
        }

        markdownRepository.deleteByRuleId(ruleId);

        log.info("MarkdownServiceImpl.removeMarkdown: removed markdown for ruleId={}", ruleId);
    }

    @Override
    public List<MarkdownApplied> recalculateMarkdown(String ruleId) {
        log.info("MarkdownServiceImpl.recalculateMarkdown: ruleId={}", ruleId);

        List<MarkdownApplied> markdowns = markdownRepository.findByRuleId(ruleId);
        if (markdowns.isEmpty()) {
            log.debug("MarkdownServiceImpl.recalculateMarkdown: no markdown found for ruleId={}", ruleId);
            return List.of();
        }

        List<MarkdownApplied> updated = new ArrayList<>();

        for (MarkdownApplied markdown : markdowns) {
            Sku sku = skuRepository.findByIdForUpdate(markdown.getSkuId()).orElse(null);
            if (sku == null) continue;

            BigDecimal calculatedPrice = markdownCalculator.calculate(
                    sku.getOriginalPrice(),
                    markdown.getDiscountType(),
                    markdown.getDiscountValue(),
                    markdown.getMaxDiscountAmount()
            );
            sku.setPromotionalPrice(calculatedPrice);
            sku.setUpdatedAt(LocalDateTime.now());
            Sku savedSku = skuRepository.save(sku);

            markdown.setCalculatedPrice(calculatedPrice);
            MarkdownApplied savedMarkdown = markdownRepository.save(markdown);

            Product product = productRepository.findById(markdown.getProductId()).orElse(null);
            if (product != null) {
                List<DomainEvent> events = new ArrayList<>();
                events.add(new SkuUpdatedEvent(
                        savedSku,
                        product.getName(),
                        product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                        product.getStatus().name(),
                        List.of("promotionalPrice"),
                        "MARKDOWN_RECALCULATED"
                ));
                saveOutboxEvents(events);
            }

            setMarkdownCache(savedSku.getId().getValue(), savedMarkdown);
            updated.add(savedMarkdown);
        }

        log.info("MarkdownServiceImpl.recalculateMarkdown: recalculated {} markdowns for ruleId={}",
                updated.size(), ruleId);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MarkdownCacheEntry> getActiveMarkdown(String skuId) {
        return markdownCacheRepository.findBySkuId(skuId);
    }

    private void persistDelayedMarkdown(MarkdownApplication application) {
        MarkdownApplied markdown = new MarkdownApplied();
        markdown.setId(UUID.randomUUID().toString());
        markdown.setRuleId(application.ruleId());
        markdown.setProductId(ProductId.of(application.productId()));
        markdown.setSkuId(SkuId.of(application.skuId()));
        markdown.setDiscountType(application.discountType());
        markdown.setDiscountValue(application.discountValue());
        markdown.setMaxDiscountAmount(application.maxDiscountAmount());
        markdown.setAppliedAt(LocalDateTime.now());
        markdown.setExpiresAt(application.expiresAt());
        markdownRepository.save(markdown);
        log.info("MarkdownServiceImpl: persisted delayed markdown for ruleId={}, starts at {}",
                application.ruleId(), application.startTime());
    }

    private void setMarkdownCache(String skuId, MarkdownApplied markdown) {
        try {
            MarkdownCacheEntry entry = MarkdownCacheEntry.builder()
                    .ruleId(markdown.getRuleId())
                    .discountType(markdown.getDiscountType().name())
                    .discountValue(markdown.getDiscountValue())
                    .calculatedPrice(markdown.getCalculatedPrice())
                    .expiresAt(markdown.getExpiresAt() != null
                            ? markdown.getExpiresAt().atZone(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            : null)
                    .build();

            long ttlSeconds = markdown.getExpiresAt() != null
                    ? Math.max(0, markdown.getExpiresAt().toEpochSecond(java.time.ZoneOffset.UTC)
                            - System.currentTimeMillis() / 1000) + MARKDOWN_CACHE_TTL_BUFFER_SECONDS
                    : Duration.ofHours(24).toSeconds();

            markdownCacheRepository.save(skuId, entry, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("MarkdownServiceImpl: failed to set markdown cache for sku {}: {}",
                    skuId, e.getMessage());
        }
    }

    private void saveOutboxEvents(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) return;

        try {
            List<OutboxEvent> outboxEvents = new ArrayList<>();
            for (DomainEvent event : events) {
                String payload = objectMapper.writeValueAsString(event.toCloudEventPayload());
                OutboxEvent outboxEvent = OutboxEvent.of(
                        event,
                        event.getAggregateId(),
                        event.getPartitionKey(),
                        payload
                );
                outboxEvents.add(outboxEvent);
            }
            outboxRepository.saveAll(outboxEvents);
        } catch (Exception e) {
            log.error("MarkdownServiceImpl: failed to save outbox events: {}", e.getMessage(), e);
        }
    }
}
