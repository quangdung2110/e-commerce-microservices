package com.platform.productservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.entity.Sku;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.domain.event.ProductCreatedEvent;
import com.platform.productservice.domain.event.ProductDeletedEvent;
import com.platform.productservice.domain.event.ProductStatusChangedEvent;
import com.platform.productservice.domain.event.ProductUpdatedEvent;
import com.platform.productservice.domain.exception.DomainException;
import com.platform.productservice.domain.exception.ProductNotFoundException;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.domain.vo.Slug;
import com.platform.productservice.dto.mapper.ProductMapper;
import com.platform.productservice.infrastructure.redis.cache.model.SkuCacheEntry;
import com.platform.productservice.port.inbound.ProductServicePort;
import com.platform.productservice.port.outbound.cache.SkuCacheRepository;
import com.platform.productservice.port.outbound.database.CategoryRepository;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import com.platform.productservice.service.ProductFsmFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.platform.productservice.common.constants.ServiceConstants.SKU_CACHE_TTL_SECONDS;

/**
 * Product service implementation managing the full product lifecycle.
 * Coordinates between domain, repositories, cache, and FSM facade.
 */
@Service
@Transactional
public class ProductServiceImpl implements ProductServicePort {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final OutboxRepository outboxRepository;
    private final SkuCacheRepository skuCacheRepository;
    private final ProductFsmFacade productFsmFacade;
    private final ProductMapper productMapper;
    private final ObjectMapper objectMapper;

    public ProductServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            OutboxRepository outboxRepository,
            SkuCacheRepository skuCacheRepository,
            ProductFsmFacade productFsmFacade,
            ProductMapper productMapper,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.categoryRepository = categoryRepository;
        this.outboxRepository = outboxRepository;
        this.skuCacheRepository = skuCacheRepository;
        this.productFsmFacade = productFsmFacade;
        this.productMapper = productMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Product create(Product product) {
        log.info("ProductServiceImpl.create: name={}", product.getName());

        if (product.getCategoryId() != null) {
            categoryRepository.findById(product.getCategoryId())
                    .orElseThrow(() -> new DomainException(
                            "Category not found: " + product.getCategoryId().getValue()));
        }

        if (product.getSlug() != null && productRepository.existsBySlug(product.getSlug())) {
            throw new DomainException("Duplicate slug: " + product.getSlug());
        }

        product.initialize();

        Product saved = productRepository.save(product);

        for (Sku sku : saved.getSkus()) {
            Sku savedSku = skuRepository.save(sku);
            savedSku.setProductId(saved.getId());
            skuRepository.save(savedSku);
        }

        List<DomainEvent> events = new ArrayList<>();
        events.add(new ProductCreatedEvent(saved));
        saveOutboxEvents(events);

        warmCacheForSkus(saved);

        log.info("ProductServiceImpl.create: created product id={}", saved.getId().getValue());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Product findById(String productId) {
        ProductId id = ProductId.of(productId);

        Optional<SkuCacheEntry> cached = skuCacheRepository.findById(productId);
        if (cached.isPresent()) {
            log.debug("ProductServiceImpl.findById: cache hit for productId={}", productId);
            return productRepository.findById(id).orElse(null);
        }

        log.debug("ProductServiceImpl.findById: cache miss for productId={}, falling back to DB", productId);
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> search(ProductSearchCriteria criteria) {
        Pageable pageable = buildPageable(criteria);
        return productRepository.findByFilters(
                criteria.categoryId(),
                criteria.status(),
                criteria.search(),
                false,
                pageable
        );
    }

    @Override
    public Product update(String productId, Product updatedData) {
        log.info("ProductServiceImpl.update: productId={}", productId);

        ProductId id = ProductId.of(productId);
        Product existing = productRepository.findByIdForNoKeyUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (updatedData.getName() != null) existing.setName(updatedData.getName());
        if (updatedData.getDescription() != null) existing.setDescription(updatedData.getDescription());
        if (updatedData.getCategoryId() != null) existing.setCategoryId(updatedData.getCategoryId());
        if (updatedData.getAttributes() != null) existing.setAttributes(updatedData.getAttributes());
        existing.setUpdatedAt(java.time.LocalDateTime.now());

        if (updatedData.getSlug() != null && !updatedData.getSlug().equals(existing.getSlug())) {
            if (productRepository.existsBySlug(updatedData.getSlug())) {
                throw new DomainException("Duplicate slug: " + updatedData.getSlug());
            }
            existing.setSlug(updatedData.getSlug());
        }

        Product saved = productRepository.save(existing);

        List<String> changedFields = detectChangedFields(existing, updatedData);
        List<String> skuIds = existing.getSkus() != null
                ? existing.getSkus().stream().map(s -> s.getId().getValue()).toList()
                : List.of();

        List<DomainEvent> events = new ArrayList<>();
        events.add(new ProductUpdatedEvent(
                saved.getId().getValue(),
                changedFields,
                skuIds,
                saved.getUpdatedAt() != null ? saved.getUpdatedAt().toString() : null
        ));
        saveOutboxEvents(events);

        if (saved.getSkus() != null) {
            for (Sku sku : saved.getSkus()) {
                warmCacheForSku(saved, sku);
            }
        }

        log.info("ProductServiceImpl.update: updated product id={}", productId);
        return saved;
    }

    @Override
    public void delete(String productId) {
        log.info("ProductServiceImpl.delete: productId={}", productId);

        ProductId id = ProductId.of(productId);
        Product product = productRepository.findByIdForNoKeyUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (!product.canBeDeleted()) {
            throw new DomainException(
                    "Product can only be deleted from DRAFT or HIDDEN status. Current status: "
                            + product.getStatus().name());
        }

        product.markDeleted();
        Product saved = productRepository.save(product);

        List<String> skuIds = product.getSkus() != null
                ? product.getSkus().stream().map(s -> s.getId().getValue()).toList()
                : List.of();

        for (Sku sku : product.getSkus()) {
            sku.markDeleted();
            skuRepository.save(sku);
            skuCacheRepository.delete(sku.getId().getValue());
        }

        List<DomainEvent> events = new ArrayList<>();
        events.add(new ProductDeletedEvent(
                saved.getId().getValue(),
                skuIds,
                saved.getUpdatedAt() != null ? saved.getUpdatedAt().toString() : null,
                saved.getDeletedAt() != null ? saved.getDeletedAt().toString() : null
        ));
        saveOutboxEvents(events);

        log.info("ProductServiceImpl.delete: deleted product id={}", productId);
    }

    @Override
    public Product submit(String productId) {
        log.info("ProductServiceImpl.submit: productId={}", productId);

        ProductId id = ProductId.of(productId);
        Product product = productRepository.findByIdForNoKeyUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Product updated = productFsmFacade.submit(product);

        log.info("ProductServiceImpl.submit: submitted product id={}", productId);
        return updated;
    }

    @Override
    public Product activate(String productId) {
        log.info("ProductServiceImpl.activate: productId={}", productId);
        return submit(productId);
    }

    @Override
    public Product hide(String productId) {
        log.info("ProductServiceImpl.hide: productId={}", productId);

        ProductId id = ProductId.of(productId);
        Product product = productRepository.findByIdForNoKeyUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Product updated = productFsmFacade.hide(product);

        log.info("ProductServiceImpl.hide: hidden product id={}", productId);
        return updated;
    }

    @Override
    public Product show(String productId) {
        log.info("ProductServiceImpl.show: productId={}", productId);

        ProductId id = ProductId.of(productId);
        Product product = productRepository.findByIdForNoKeyUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        Product updated = productFsmFacade.show(product);

        log.info("ProductServiceImpl.show: showed product id={}", productId);
        return updated;
    }

    private void warmCacheForSkus(Product product) {
        if (product.getSkus() == null || product.getSkus().isEmpty()) return;

        Duration ttl = Duration.ofSeconds(SKU_CACHE_TTL_SECONDS);
        for (Sku sku : product.getSkus()) {
            if (!sku.isDeleted()) {
                warmCacheForSku(product, sku, ttl);
            }
        }
    }

    private void warmCacheForSku(Product product, Sku sku) {
        warmCacheForSku(product, sku, Duration.ofSeconds(SKU_CACHE_TTL_SECONDS));
    }

    private void warmCacheForSku(Product product, Sku sku, Duration ttl) {
        try {
            SkuCacheEntry entry = SkuCacheEntry.fromDomain(product, sku);
            skuCacheRepository.save(sku.getId().getValue(), entry, ttl);
            log.debug("ProductServiceImpl: warmed cache for sku={}", sku.getId().getValue());
        } catch (Exception e) {
            log.warn("ProductServiceImpl: failed to warm cache for sku={}: {}",
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
            log.error("ProductServiceImpl: failed to save outbox events: {}", e.getMessage(), e);
        }
    }

    private List<String> detectChangedFields(Product existing, Product updated) {
        List<String> changedFields = new ArrayList<>();
        if (updated.getName() != null && !updated.getName().equals(existing.getName())) {
            changedFields.add("name");
        }
        if (updated.getDescription() != null && !updated.getDescription().equals(existing.getDescription())) {
            changedFields.add("description");
        }
        if (updated.getSlug() != null && !updated.getSlug().equals(existing.getSlug())) {
            changedFields.add("slug");
        }
        if (updated.getCategoryId() != null && !updated.getCategoryId().equals(existing.getCategoryId())) {
            changedFields.add("categoryId");
        }
        if (updated.getAttributes() != null && !updated.getAttributes().equals(existing.getAttributes())) {
            changedFields.add("attributes");
        }
        return changedFields;
    }

    private Pageable buildPageable(ProductSearchCriteria criteria) {
        int page = Math.max(0, criteria.page());
        int size = Math.min(Math.max(1, criteria.size()), 100);
        Sort sort = Sort.unsorted();

        if (criteria.sort() != null && !criteria.sort().isBlank()) {
            String[] parts = criteria.sort().split(",");
            String field = parts[0].trim();
            org.springframework.data.domain.Sort.Direction direction =
                    parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")
                            ? Sort.Direction.ASC
                            : Sort.Direction.DESC;
            sort = Sort.by(direction, field);
        }

        return PageRequest.of(page, size, sort);
    }
}
