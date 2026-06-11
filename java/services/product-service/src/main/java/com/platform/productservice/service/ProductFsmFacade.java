package com.platform.productservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.common.constants.ServiceConstants;
import com.platform.productservice.domain.entity.OutboxEvent;
import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.event.ProductStatusChangedEvent;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import com.platform.productservice.domain.vo.ProductId;
import com.platform.productservice.port.outbound.database.OutboxRepository;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.port.outbound.database.SkuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * High-level FSM facade that wraps {@link ProductStateMachineService} with
 * domain event publishing and database persistence.
 *
 * <p>This facade is the entry point for all product lifecycle operations.
 * It provides a clean API for:</p>
 * <ul>
 *   <li>Admin-initiated transitions: submit, hide, show</li>
 *   <li>System-initiated transitions: autoHideOutOfStock, autoReactivateInStock</li>
 * </ul>
 *
 * <p>After every successful transition, this facade:</p>
 * <ol>
 *   <li>Persists the updated Product entity to the database</li>
 *   <li>Publishes a {@link ProductStatusChangedEvent} to the outbox</li>
 * </ol>
 */
@Service
public class ProductFsmFacade {

    private static final Logger log = LoggerFactory.getLogger(ProductFsmFacade.class);

    private final ProductStateMachineService stateMachineService;
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public ProductFsmFacade(
            ProductStateMachineService stateMachineService,
            ProductRepository productRepository,
            SkuRepository skuRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.stateMachineService = stateMachineService;
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Submits a product for activation (DRAFT -> ACTIVE).
     * Requires at least one SKU with originalPrice > 0.
     */
    @Transactional
    public Product submit(Product product) {
        log.info("ProductFsmFacade.submit: product={}", product.getId().getValue());
        ProductStateMachineService.TransitionResult result =
                stateMachineService.sendEvent(product, ProductEvent.SUBMIT);

        if (!result.isAccepted()) {
            throw new com.platform.productservice.domain.exception.InvalidStateTransitionException(
                    "SUBMIT", "ACTIVE", result.getErrorMessage());
        }

        Product saved = productRepository.save(result.getProduct());
        publishStatusChangedEvent(saved, ProductState.DRAFT, ProductState.ACTIVE,
                "SUBMIT", "ADMIN_SET_ACTIVE",
                "Product submitted for activation", result.getSkuIds());
        return saved;
    }

    /**
     * Manually hides a product (ACTIVE -> HIDDEN).
     * Only allowed from ACTIVE state.
     */
    @Transactional
    public Product hide(Product product) {
        log.info("ProductFsmFacade.hide: product={}", product.getId().getValue());
        ProductStateMachineService.TransitionResult result =
                stateMachineService.sendEvent(product, ProductEvent.HIDE);

        if (!result.isAccepted()) {
            throw new com.platform.productservice.domain.exception.InvalidStateTransitionException(
                    "ACTIVE", "HIDDEN", result.getErrorMessage());
        }

        Product saved = productRepository.save(result.getProduct());
        publishStatusChangedEvent(saved, ProductState.ACTIVE, ProductState.HIDDEN,
                "HIDE", "ADMIN_SET_INACTIVE",
                "Admin manually hid the product", result.getSkuIds());
        return saved;
    }

    /**
     * Manually shows a product (HIDDEN -> ACTIVE).
     * Requires at least one SKU with available stock.
     */
    @Transactional
    public Product show(Product product) {
        log.info("ProductFsmFacade.show: product={}", product.getId().getValue());
        ProductStateMachineService.TransitionResult result =
                stateMachineService.sendEvent(product, ProductEvent.SHOW);

        if (!result.isAccepted()) {
            throw new com.platform.productservice.domain.exception.InvalidStateTransitionException(
                    "HIDDEN", "ACTIVE", result.getErrorMessage());
        }

        Product saved = productRepository.save(result.getProduct());
        publishStatusChangedEvent(saved, ProductState.HIDDEN, ProductState.ACTIVE,
                "SHOW", "ADMIN_SET_ACTIVE",
                "Admin manually showed the product", result.getSkuIds());
        return saved;
    }

    /**
     * System-initiated auto-hide when all SKUs run out of stock (ACTIVE -> HIDDEN).
     * Does NOT set adminHidden = true, allowing auto-reactivation later.
     */
    @Transactional
    public Product autoHideOutOfStock(Product product) {
        log.info("ProductFsmFacade.autoHideOutOfStock: product={}", product.getId().getValue());
        ProductStateMachineService.TransitionResult result =
                stateMachineService.sendEvent(product, ProductEvent.AUTO_HIDE_OUT_OF_STOCK);

        if (!result.isAccepted()) {
            log.warn("ProductFsmFacade.autoHideOutOfStock: event not accepted for product {}: {}",
                    product.getId().getValue(), result.getErrorMessage());
            return product;
        }

        Product saved = productRepository.save(result.getProduct());
        publishStatusChangedEvent(saved, ProductState.ACTIVE, ProductState.HIDDEN,
                "AUTO_HIDE_OUT_OF_STOCK", "AUTO_HIDE_OUT_OF_STOCK",
                "Auto-hide triggered: all SKUs are out of stock", result.getSkuIds());
        return saved;
    }

    /**
     * System-initiated auto-reactivation when stock returns (HIDDEN -> ACTIVE).
     * Only allowed when adminHidden = false.
     */
    @Transactional
    public Product autoReactivateInStock(Product product) {
        log.info("ProductFsmFacade.autoReactivateInStock: product={}", product.getId().getValue());
        ProductStateMachineService.TransitionResult result =
                stateMachineService.sendEvent(product, ProductEvent.AUTO_REACTIVATE_IN_STOCK);

        if (!result.isAccepted()) {
            log.warn("ProductFsmFacade.autoReactivateInStock: event not accepted for product {}: {}",
                    product.getId().getValue(), result.getErrorMessage());
            return product;
        }

        Product saved = productRepository.save(result.getProduct());
        publishStatusChangedEvent(saved, ProductState.HIDDEN, ProductState.ACTIVE,
                "AUTO_REACTIVATE_IN_STOCK", "AUTO_REACTIVATE_IN_STOCK",
                "Auto-reactivate triggered: stock returned while not admin-hidden", result.getSkuIds());
        return saved;
    }

    /**
     * Checks whether a given event can be applied to the product.
     */
    public boolean canTransition(Product product, ProductEvent event) {
        return stateMachineService.canTransition(product, event);
    }

    /**
     * Gets the current state of a product.
     */
    public ProductState getCurrentState(ProductId productId) {
        return stateMachineService.getCurrentState(productId.getValue());
    }

    private void publishStatusChangedEvent(Product product, ProductState oldStatus,
                                           ProductState newStatus, String changedBy,
                                           String changedByType, String reason,
                                           List<String> skuIds) {
        try {
            ProductStatusChangedEvent domainEvent = new ProductStatusChangedEvent(
                    product.getId().getValue(),
                    oldStatus.name(),
                    newStatus.name(),
                    changedBy,
                    changedByType,
                    reason,
                    skuIds
            );

            String payload = objectMapper.writeValueAsString(domainEvent.toCloudEventPayload());

            OutboxEvent outboxEvent = OutboxEvent.of(
                    domainEvent,
                    product.getId().getValue(),
                    product.getId().getValue(),
                    payload
            );

            outboxRepository.save(outboxEvent);
            log.info("ProductFsmFacade: published ProductStatusChangedEvent for product {} ({} -> {})",
                    product.getId().getValue(), oldStatus, newStatus);
        } catch (JsonProcessingException e) {
            log.error("ProductFsmFacade: failed to serialize ProductStatusChangedEvent for product {}: {}",
                    product.getId().getValue(), e.getMessage(), e);
        }
    }
}
