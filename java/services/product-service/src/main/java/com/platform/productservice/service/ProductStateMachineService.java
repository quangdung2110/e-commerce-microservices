package com.platform.productservice.service;

import com.platform.productservice.domain.entity.Product;
import com.platform.productservice.domain.exception.InvalidStateTransitionException;
import com.platform.productservice.domain.statemachine.ProductEvent;
import com.platform.productservice.domain.statemachine.ProductState;
import com.platform.productservice.port.outbound.database.ProductRepository;
import com.platform.productservice.domain.vo.ProductId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Low-level service that manages Spring State Machine instances for individual products.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Create and configure a state machine for a given product</li>
 *   <li>Send events to trigger state transitions</li>
 *   <li>Load product state into extended state before sending events</li>
 *   <li>Persist state machine state after transitions</li>
 *   <li>Return transition results including whether guards passed or failed</li>
 * </ul>
 *
 * <p>Note: This service handles state machine lifecycle but does NOT persist the
 * domain Product entity. That responsibility belongs to {@link ProductFsmFacade}.
 */
@Service
public class ProductStateMachineService {

    private static final Logger log = LoggerFactory.getLogger(ProductStateMachineService.class);

    private final StateMachineFactory<ProductState, ProductEvent> stateMachineFactory;
    private final ProductRepository productRepository;

    public ProductStateMachineService(
            StateMachineFactory<ProductState, ProductEvent> stateMachineFactory,
            ProductRepository productRepository) {
        this.stateMachineFactory = stateMachineFactory;
        this.productRepository = productRepository;
    }

    /**
     * Creates a new state machine instance for the given product ID.
     * The state machine is initialized with the product's current state.
     *
     * @param productId the product ID
     * @return a started state machine instance
     */
    public StateMachine<ProductState, ProductEvent> build(ProductId productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId.getValue()));

        ProductState currentState = ProductState.fromString(product.getStatus().name());

        StateMachine<ProductState, ProductEvent> sm = stateMachineFactory.getStateMachine(productId.getValue());

        sm.getStateMachineAccessor().doWithAllRegions(accessor -> {
            accessor.resetStateMachineReactively(
                    new DefaultStateMachineContext<>(currentState, null, null, null)
            ).block();
        });

        sm.getExtendedState().getVariables().put("product", product);
        sm.startReactively().block();

        return sm;
    }

    /**
     * Sends an event to trigger a state transition on the product.
     *
     * <p>Lifecycle:</p>
     * <ol>
     *   <li>Acquire lock on product (via pessimistic locking)</li>
     *   <li>Build state machine with current state and load product into extended state</li>
     *   <li>Send the event</li>
     *   <li>Persist updated product back to database</li>
     *   <li>Stop the state machine</li>
     * </ol>
     *
     * @param product the product entity (loaded with lock)
     * @param event  the event to send
     * @return the transition result, never null
     */
    public TransitionResult sendEvent(Product product, ProductEvent event) {
        ProductId productId = product.getId();
        log.info("sendEvent: product={}, event={}", productId.getValue(), event.name());

        StateMachine<ProductState, ProductEvent> sm = null;
        try {
            sm = build(productId);

            Message<ProductEvent> message = MessageBuilder
                    .withPayload(event)
                    .setHeader("productId", productId.getValue())
                    .build();

            org.springframework.statemachine.StateMachineEventResult<ProductEvent> result =
                    sm.sendEvent(Mono.just(message)).blockLast();

            boolean success = result != null && result.getResultType()
                    == org.springframework.statemachine.StateMachineEventResult.ResultType.ACCEPTED;

            if (success) {
                Product updatedProduct =
                        sm.getExtendedState().get("product", Product.class);

                ProductState from = sm.getState().getId();
                log.info("sendEvent: product {} transitioned {} -> {} via event {}",
                        productId.getValue(), from, updatedProduct.getStatus().name(), event.name());
                return TransitionResult.accepted(event, from, updatedProduct.getStatus(), updatedProduct);
            } else {
                String error = (String) sm.getExtendedState().getVariables().get("guardError");
                return TransitionResult.rejected(event, error != null ? error : "Transition rejected by guard");
            }
        } catch (Exception e) {
            log.error("sendEvent: error sending event {} to product {}: {}",
                    event, productId.getValue(), e.getMessage(), e);
            throw new InvalidStateTransitionException(
                    "Failed to send event " + event + " to product " + productId.getValue() + ": " + e.getMessage());
        } finally {
            if (sm != null) {
                try {
                    sm.stopReactively().block();
                } catch (Exception e) {
                    log.warn("sendEvent: failed to stop state machine for product {}: {}",
                            productId.getValue(), e.getMessage());
                }
            }
        }
    }

    /**
     * Returns the current state of a product from the database.
     *
     * @param productId the product ID
     * @return the current ProductState
     */
    public ProductState getCurrentState(String productId) {
        return productRepository.findById(ProductId.of(productId))
                .map(p -> ProductState.fromString(p.getStatus().name()))
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
    }

    /**
     * Checks if a transition is valid without actually performing it.
     *
     * @param product the product entity
     * @param event  the event to check
     * @return true if the transition can be performed
     */
    public boolean canTransition(Product product, ProductEvent event) {
        StateMachine<ProductState, ProductEvent> sm = null;
        try {
            sm = build(product.getId());
            Message<ProductEvent> message = MessageBuilder
                    .withPayload(event)
                    .build();

            org.springframework.statemachine.StateMachineEventResult<ProductEvent> result =
                    sm.sendEvent(Mono.just(message)).blockLast();

            return result != null && result.getResultType()
                    == org.springframework.statemachine.StateMachineEventResult.ResultType.ACCEPTED;
        } finally {
            if (sm != null) {
                sm.stopReactively().block();
            }
        }
    }

    /**
     * Holds the result of a state machine event send operation.
     */
    public static class TransitionResult {
        private final boolean accepted;
        private final ProductEvent event;
        private final ProductState fromState;
        private final ProductState toState;
        private final Product product;
        private final String errorMessage;

        private TransitionResult(boolean accepted, ProductEvent event, ProductState fromState,
                                ProductState toState, Product product, String errorMessage) {
            this.accepted = accepted;
            this.event = event;
            this.fromState = fromState;
            this.toState = toState;
            this.product = product;
            this.errorMessage = errorMessage;
        }

        public static TransitionResult accepted(ProductEvent event, ProductState fromState,
                                                 ProductState toState, Product product) {
            return new TransitionResult(true, event, fromState, toState, product, null);
        }

        public static TransitionResult rejected(ProductEvent event, String errorMessage) {
            return new TransitionResult(false, event, null, null, null, errorMessage);
        }

        public boolean isAccepted() { return accepted; }
        public ProductEvent getEvent() { return event; }
        public ProductState getFromState() { return fromState; }
        public ProductState getToState() { return toState; }
        public Product getProduct() { return product; }
        public String getErrorMessage() { return errorMessage; }
        public List<String> getSkuIds() {
            return product != null && product.getSkus() != null
                    ? product.getSkus().stream()
                            .filter(sku -> !sku.isDeleted())
                            .map(sku -> sku.getId().getValue())
                            .toList()
                    : List.of();
        }
    }
}
