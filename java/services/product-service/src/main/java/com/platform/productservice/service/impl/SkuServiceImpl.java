package com.platform.productservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.SkuCreatedEvent;
import com.platform.productservice.domain.event.SkuDeletedEvent;
import com.platform.productservice.domain.event.SkuUpdatedEvent;
import com.platform.productservice.domain.exception.DomainException;
import com.platform.productservice.domain.exception.ProductNotFoundException;
import com.platform.productservice.domain.exception.SkuNotFoundException;
import com.platform.productservice.domain.service.MarkdownCalculator;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuCode;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.infrastructure.redis.cache.model.MarkdownCacheEntry;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.inbound.SkuServicePort;
import com.platform.productservice.port.outbound.cache.MarkdownCacheRepository;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.platform.productservice.common.constants.ServiceConstants.SKU_CACHE_TTL_SECONDS;

/**
 * SKU service implementation managing SKU lifecycle within products.
 * Coordinates between domain, repositories, cache, and markdown calculator.
 */
@Service
@Transactional
public class SkuServiceImpl implements SkuServicePort {

    private static final Logger log = LoggerFactory.getLogger(SkuServiceImpl.class);

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;
    private final OutboxRepository outboxRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final MarkdownCalculator markdownCalculator;
    private final MarkdownCacheRepository markdownCacheRepository;
    private final ObjectMapper objectMapper;

    public SkuServiceImpl(
            SkuRepository skuRepository,
            ProductRepository productRepository,
            OutboxRepository outboxRepository,
            SkuCacheRepository skuCacheRepository,
            MarkdownCalculator markdownCalculator,
            MarkdownCacheRepository markdownCacheRepository,
            ObjectMapper objectMapper) {
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
        this.outboxRepository = outboxRepository;
        this.skuCacheRepository = skuCacheRepository;
        this.markdownCalculator = markdownCalculator;
        this.markdownCacheRepository = markdownCacheRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Sku createSku(String productId, Sku sku) {
        log.info("SkuServiceImpl.createSku: productId={}, skuCode={}", productId, sku.getSkuCode());

        ProductId pid = ProductId.of(productId);
        Product product = productRepository.findByIdForNoKeyUpdate(pid)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.isDeleted()) {
            throw new DomainException("Cannot add SKU to a deleted product: " + productId);
        }

        if (sku.getSkuCode() != null && skuRepository.findBySkuCode(sku.getSkuCode()).isPresent()) {
            throw new DomainException("Duplicate SKU code: " + sku.getSkuCode().getValue());
        }

        SkuId generatedId = SkuId.generate();
        sku.setId(generatedId);
        sku.setProductId(pid);
        sku.initialize(pid);

        Sku saved = skuRepository.save(sku);

        product.addSku(saved);
        productRepository.save(product);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new SkuCreatedEvent(
                saved,
                product.getName(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                product.getStatus().name()
        ));
        saveOutboxEvents(events);

        warmCacheForSku(product, saved);

        log.info("SkuServiceImpl.createSku: created sku id={}", saved.getId().getValue());
        return saved;
    }

    @Override
    public Sku updateSku(String productId, String skuId, Sku updatedData) {
        log.info("SkuServiceImpl.updateSku: productId={}, skuId={}", productId, skuId);

        ProductId pid = ProductId.of(productId);
        SkuId sid = SkuId.of(skuId);

        Product product = productRepository.findByIdForNoKeyUpdate(pid)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Sku sku = skuRepository.findWithLockById(sid)
                .orElseThrow(() -> new SkuNotFoundException(skuId));

        BigDecimal originalPrice = sku.getOriginalPrice();

        if (updatedData.getSkuCode() != null) sku.setSkuCode(updatedData.getSkuCode());
        if (updatedData.getOriginalPrice() != null) sku.setOriginalPrice(updatedData.getOriginalPrice());
        if (updatedData.getPromotionalPrice() != null) sku.setPromotionalPrice(updatedData.getPromotionalPrice());
        if (updatedData.getDisplayQuantity() != existing.getDisplayQuantity()) {
            sku.setDisplayQuantity(updatedData.getDisplayQuantity());
        }
        if (updatedData.getVariantAttributes() != null) sku.setVariantAttributes(updatedData.getVariantAttributes());
        sku.setUpdatedAt(LocalDateTime.now());

        if (updatedData.getOriginalPrice() != null
                && !updatedData.getOriginalPrice().equals(originalPrice)
                && sku.hasPromotion()) {
            recalculateMarkdownForSku(sku);
        }

        Sku saved = skuRepository.save(sku);

        List<String> changedFields = detectChangedFields(sku, updatedData, originalPrice);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new SkuUpdatedEvent(
                saved,
                product.getName(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                product.getStatus().name(),
                changedFields,
                "ADMIN_UPDATE"
        ));
        saveOutboxEvents(events);

        warmCacheForSku(product, saved);

        log.info("SkuServiceImpl.updateSku: updated sku id={}", skuId);
        return saved;
    }

    @Override
    public void deleteSku(String productId, String skuId) {
        log.info("SkuServiceImpl.deleteSku: productId={}, skuId={}", productId, skuId);

        ProductId pid = ProductId.of(productId);
        SkuId sid = SkuId.of(skuId);

        Product product = productRepository.findByIdForNoKeyUpdate(pid)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Sku sku = skuRepository.findByIdForUpdate(sid)
                .orElseThrow(() -> new SkuNotFoundException(skuId));

        sku.markDeleted();
        skuRepository.save(sku);

        product.removeSku(sid);
        productRepository.save(product);

        List<DomainEvent> events = new ArrayList<>();
        events.add(new SkuDeletedEvent(
                product.getId().getValue(),
                product.getName(),
                product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                null,
                sku.getId().getValue(),
                sku.getSkuCode(),
                sku.getUpdatedAt() != null ? sku.getUpdatedAt().toString() : null,
                sku.getDeletedAt() != null ? sku.getDeletedAt().toString() : null
        ));
        saveOutboxEvents(events);

        skuCacheRepository.delete(skuId);

        log.info("SkuServiceImpl.deleteSku: deleted sku id={}", skuId);
    }

    @Override
    @Transactional(readOnly = true)
    public Sku findById(String skuId) {
        SkuId id = SkuId.of(skuId);
        return skuRepository.findByIdAndNotDeleted(id)
                .orElseThrow(() -> new SkuNotFoundException(skuId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Sku> findByProductId(String productId) {
        ProductId pid = ProductId.of(productId);
        return skuRepository.findByProductId(pid);
    }

    private void recalculateMarkdownForSku(Sku sku) {
        Optional<MarkdownCacheEntry> cachedMarkdown = markdownCacheRepository.findBySkuId(sku.getId().getValue());
        if (cachedMarkdown.isPresent()) {
            MarkdownCacheEntry entry = cachedMarkdown.get();
            try {
                BigDecimal calculated = markdownCalculator.calculate(
                        sku.getOriginalPrice(),
                        com.platform.productservice.domain.enums.DiscountType.valueOf(entry.getDiscountType()),
                        entry.getDiscountValue(),
                        null
                );
                sku.setPromotionalPrice(calculated);
            } catch (Exception e) {
                log.warn("SkuServiceImpl: failed to recalculate markdown for sku {}: {}",
                        sku.getId().getValue(), e.getMessage());
            }
        }
    }

    private void warmCacheForSku(Product product, Sku sku) {
        try {
            SkuCacheEntry entry = SkuCacheEntry.fromDomain(product, sku);
            skuCacheRepository.save(sku.getId().getValue(), entry, Duration.ofSeconds(SKU_CACHE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("SkuServiceImpl: failed to warm cache for sku {}: {}",
                    sku.getId().getValue(), e.getMessage());
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
            log.error("SkuServiceImpl: failed to save outbox events: {}", e.getMessage(), e);
        }
    }

    private List<String> detectChangedFields(Sku existing, Sku updated, BigDecimal originalPrice) {
        List<String> changedFields = new ArrayList<>();
        if (updated.getSkuCode() != null && !updated.getSkuCode().equals(existing.getSkuCode())) {
            changedFields.add("skuCode");
        }
        if (updated.getOriginalPrice() != null && !updated.getOriginalPrice().equals(originalPrice)) {
            changedFields.add("originalPrice");
        }
        if (updated.getPromotionalPrice() != null && !updated.getPromotionalPrice().equals(existing.getPromotionalPrice())) {
            changedFields.add("promotionalPrice");
        }
        if (updated.getDisplayQuantity() != existing.getDisplayQuantity()) {
            changedFields.add("displayQuantity");
        }
        if (updated.getVariantAttributes() != null && !updated.getVariantAttributes().equals(existing.getVariantAttributes())) {
            changedFields.add("variantAttributes");
        }
        return changedFields;
    }
}
