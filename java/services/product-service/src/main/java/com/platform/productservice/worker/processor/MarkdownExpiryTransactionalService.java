package com.platform.productservice.worker.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.MarkdownApplied;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.SkuUpdatedEvent;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.SkuId;
import com.platform.productservice.port.outbound.cache.MarkdownCacheRepository;
import com.platform.productservice.port.outbound.database.MarkdownRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Transactional service for processing expired markdowns within a single DB transaction.
 * Extracted as a separate bean to avoid Spring AOP proxy self-invocation issues
 * when called from MarkdownExpiryProcessor.
 */
@Service
public class MarkdownExpiryTransactionalService {

    private static final Logger log = LoggerFactory.getLogger(MarkdownExpiryTransactionalService.class);

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final MarkdownRepository markdownRepository;
    private final OutboxRepository outboxRepository;
    private final MarkdownCacheRepository markdownCacheRepository;
    private final ObjectMapper objectMapper;

    public MarkdownExpiryTransactionalService(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            MarkdownRepository markdownRepository,
            OutboxRepository outboxRepository,
            MarkdownCacheRepository markdownCacheRepository,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.markdownRepository = markdownRepository;
        this.outboxRepository = outboxRepository;
        this.markdownCacheRepository = markdownCacheRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes expired markdowns for a single product within a transaction.
     * Locks the product with FOR NO KEY UPDATE, locks all affected SKUs with FOR UPDATE,
     * resets promotionalPrice to NULL, deletes markdown records, publishes SKU_UPDATED events,
     * and removes markdown cache entries.
     *
     * @param productId the product ID
     * @param markdowns the expired markdown records for this product
     * @return true if processed successfully
     */
    @Transactional
    public boolean processProductInTransaction(String productId, List<MarkdownApplied> markdowns) {
        ProductId pid = ProductId.of(productId);

        Product product = productRepository.findByIdForNoKeyUpdate(pid).orElse(null);
        if (product == null) {
            log.warn("MarkdownExpiryTransactionalService: product {} not found, deleting orphaned markdown records",
                    productId);
            for (MarkdownApplied m : markdowns) {
                markdownRepository.delete(m);
            }
            return true;
        }

        List<SkuId> skuIds = markdowns.stream()
                .map(MarkdownApplied::getSkuId)
                .distinct()
                .collect(Collectors.toList());

        List<Sku> lockedSkus = new ArrayList<>();
        for (SkuId sid : skuIds) {
            skuRepository.findByIdForUpdate(sid).ifPresent(lockedSkus::add);
        }

        List<DomainEvent> events = new ArrayList<>();
        List<String> deletedSkuIds = new ArrayList<>();

        for (MarkdownApplied markdown : markdowns) {
            SkuId sid = markdown.getSkuId();
            Sku sku = lockedSkus.stream()
                    .filter(s -> s.getId().equals(sid))
                    .findFirst()
                    .orElse(null);

            if (sku != null) {
                sku.setPromotionalPrice(null);
                sku.setUpdatedAt(LocalDateTime.now());
                skuRepository.save(sku);

                events.add(new SkuUpdatedEvent(
                        sku,
                        product.getName(),
                        product.getCategoryId() != null ? product.getCategoryId().getValue() : null,
                        product.getStatus().name(),
                        List.of("promotionalPrice"),
                        "MARKDOWN_ENDED"
                ));

                deletedSkuIds.add(sku.getId().getValue());

                log.debug("MarkdownExpiryTransactionalService: reset promotional price for skuId={}", sid.getValue());
            }

            markdownRepository.delete(markdown);
        }

        if (!events.isEmpty()) {
            saveOutboxEvents(events);
        }

        if (!deletedSkuIds.isEmpty()) {
            try {
                markdownCacheRepository.deleteBySkuIds(deletedSkuIds);
                log.debug("MarkdownExpiryTransactionalService: deleted {} markdown cache entries", deletedSkuIds.size());
            } catch (Exception e) {
                log.warn("MarkdownExpiryTransactionalService: failed to delete markdown cache for product {}: {}",
                        productId, e.getMessage());
            }
        }

        return true;
    }

    private void saveOutboxEvents(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
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
            log.debug("MarkdownExpiryTransactionalService: saved {} outbox events", outboxEvents.size());
        } catch (Exception e) {
            log.error("MarkdownExpiryTransactionalService: failed to save outbox events: {}", e.getMessage(), e);
        }
    }
}
