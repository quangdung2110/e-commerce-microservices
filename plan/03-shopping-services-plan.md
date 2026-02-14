# Implementation Plan: Shopping Services Group

> **Services**: Cart Service, Promotion Service, Order Service  
> **Priority**: ⭐⭐⭐⭐⭐ CRITICAL (Core transaction flow)  
> **Estimated Time**: 4-5 weeks  
> **Dependencies**: Identity Services, Product Service, Common module

---

## 📋 Overview

This group handles the entire shopping journey from cart management to order completion, including promotion engine and order state machine.

### Key Challenges
1. **Cart-Order Consistency**: Ensure prices and availability are validated
2. **Promotion Stacking**: Complex rules engine (Drools) for discounts
3. **Order State Machine**: 10-state workflow with compensation flows
4. **Temporal Saga**: Orchestrate payment + inventory + shipping
5. **Guest Cart Merge**: Merge anonymous cart when user logs in

---

## Architecture Decisions

### 1. Cart Service - Redis + PostgreSQL Hybrid
```
┌──────────────────────────────────────────┐
│  WRITE: PostgreSQL (persistence)         │
│  READ: Redis (active session cache)      │
│  TTL: Guest carts expire after 30 days   │
└──────────────────────────────────────────┘
```

### 2. Promotion Service - Drools Rules Engine
```java
rule "Buy 2 Get 1 Free"
when
    $cart : Cart()
    $count : Number(intValue >= 3) from accumulate(
        CartItem(categoryId == "electronics") from $cart.items,
        count()
    )
then
    applyDiscount($cart, calculateFreeItemDiscount($cart));
end
```

### 3. Order Service - State Machine
```
PENDING → PENDING_CONFIRMATION → PROCESSING → PAID → SHIPPED → DELIVERED
    ↓           ↓                      ↓         ↓        ↓
CANCELLED ←-----------------------------+---------+--------+
    ↓                                                     ↓
REFUNDED                                              RETURNED → REFUNDED
```

---

## Phase 1: Cart Service (Week 1)

### 1.1 Database Schema (Agent Task: DB-CART-001)

**Tables**: `carts`, `cart_items`, `saved_items`

**Key Features**:
- Dual ownership: `user_id` OR `session_id`
- Price snapshot: `unit_price_at_add` vs `current_unit_price`
- Selection flag: `is_selected` for partial checkout
- Status tracking: `ACTIVE`, `MERGED`, `CONVERTED`, `ABANDONED`

**SQL**:
```sql
CREATE TABLE carts (
    cart_id UUID PRIMARY KEY,
    user_id UUID,
    session_id VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    item_count INTEGER NOT NULL DEFAULT 0,
    subtotal DECIMAL(15, 2) NOT NULL DEFAULT 0,
    discount_total DECIMAL(15, 2) NOT NULL DEFAULT 0,
    applied_promotion_ids UUID[],
    coupon_code VARCHAR(50),
    merged_from_session VARCHAR(100),
    converted_to_order UUID,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT chk_cart_owner CHECK (user_id IS NOT NULL OR session_id IS NOT NULL)
);

CREATE UNIQUE INDEX idx_carts_user ON carts(user_id) 
WHERE user_id IS NOT NULL AND status = 'ACTIVE';

CREATE UNIQUE INDEX idx_carts_session ON carts(session_id) 
WHERE session_id IS NOT NULL AND status = 'ACTIVE';
```

### 1.2 Cart Aggregate (Agent Task: CART-AGG-001)

**Commands**:
- `AddItemCommand` → `ItemAddedToCartEvent`
- `UpdateQuantityCommand` → `ItemQuantityUpdatedEvent`
- `RemoveItemCommand` → `ItemRemovedFromCartEvent`
- `ApplyPromotionCommand` → `PromotionAppliedEvent`
- `MergeCartCommand` → `CartMergedEvent`

**Business Rules**:
```java
public void addItem(AddItemCommand cmd) {
    // Check if SKU already exists
    Optional<CartItem> existing = findItemBySku(cmd.getSkuId());
    if (existing.isPresent()) {
        updateQuantity(existing.get().getItemId(), 
            existing.get().getQuantity() + cmd.getQuantity());
        return;
    }
    
    // Validate max items (100 per cart)
    if (items.size() >= 100) {
        throw new CartLimitExceededException();
    }
    
    // Validate max quantity per item (99)
    if (cmd.getQuantity() > 99) {
        throw new InvalidQuantityException("Max 99 items per SKU");
    }
    
    apply(ItemAddedToCartEvent.builder()
        .cartId(cartId)
        .itemId(UUID.randomUUID().toString())
        .productId(cmd.getProductId())
        .skuId(cmd.getSkuId())
        .quantity(cmd.getQuantity())
        .unitPrice(cmd.getUnitPrice())
        .build());
    
    recalculateTotals();
}

public void merge(MergeCartCommand cmd) {
    List<CartItem> guestItems = cmd.getGuestItems();
    List<String> conflictedSkus = new ArrayList<>();
    
    for (CartItem guestItem : guestItems) {
        Optional<CartItem> existing = findItemBySku(guestItem.getSkuId());
        
        if (existing.isPresent()) {
            // Merge quantities (max 99)
            int newQty = Math.min(
                existing.get().getQuantity() + guestItem.getQuantity(), 
                99
            );
            updateQuantity(existing.get().getItemId(), newQty);
            conflictedSkus.add(guestItem.getSkuId());
        } else {
            addItem(mapToCommand(guestItem));
        }
    }
    
    apply(CartMergedEvent.builder()
        .cartId(cartId)
        .sourceSessionId(cmd.getGuestSessionId())
        .conflictedSkus(conflictedSkus)
        .build());
}
```

### 1.3 Cart Service (Agent Task: CART-SERVICE-001)

**Key Methods**:
- `addToCart()` - Add item with price validation
- `updateQuantity()` - Update with stock check
- `removeItem()` - Remove single item
- `clearCart()` - Remove all items
- `getCart()` - Load from Redis or PostgreSQL
- `mergeGuestCart()` - Merge on login
- `convertToOrder()` - Lock cart for checkout

**Redis Caching**:
```java
public CartDto getCart(UUID userId) {
    String cacheKey = "cart:user:" + userId;
    
    return redisService.get(cacheKey, CartDto.class)
        .orElseGet(() -> {
            Cart cart = cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseGet(() -> createNewCart(userId));
            
            CartDto dto = CartDto.from(cart);
            cacheCart(dto);
            return dto;
        });
}

private void cacheCart(CartDto cart) {
    String cacheKey = "cart:user:" + cart.getUserId();
    redisService.set(cacheKey, cart, Duration.ofHours(24));
}

private void invalidateCartCache(UUID userId, String sessionId) {
    if (userId != null) {
        redisService.delete("cart:user:" + userId);
    }
    if (sessionId != null) {
        redisService.delete("cart:session:" + sessionId);
    }
}
```

### 1.4 Cart API (Agent Task: CART-API-001)

**Endpoints**:
```java
POST   /api/cart/items              - Add item to cart
PUT    /api/cart/items/{itemId}     - Update quantity
DELETE /api/cart/items/{itemId}     - Remove item
GET    /api/cart                    - Get current cart
POST   /api/cart/apply-coupon       - Apply coupon code
DELETE /api/cart/coupon             - Remove coupon
POST   /api/cart/merge              - Merge guest cart (on login)
POST   /api/cart/clear              - Clear all items
POST   /api/cart/save-for-later     - Move item to saved list
```

---

## Phase 2: Promotion Service (Week 2)

### 2.1 Database Schema (Agent Task: DB-PROMO-001)

**Tables**: 
- `promotions` - Main promotion config
- `promotion_tiers` - Tiered/volume discounts
- `promotion_usage` - Usage tracking per user
- `coupons` - Individual coupon codes
- `promotion_stacking_rules` - Stacking logic

**Key Fields**:
```sql
CREATE TABLE promotions (
    promotion_id UUID PRIMARY KEY,
    seller_id UUID,  -- NULL = platform-wide
    name VARCHAR(200) NOT NULL,
    code VARCHAR(50),  -- For coupon codes
    promotion_type VARCHAR(30) NOT NULL CHECK (
        promotion_type IN (
            'PERCENTAGE', 'FIXED_AMOUNT', 'BUY_X_GET_Y',
            'FREE_SHIPPING', 'BUNDLE', 'TIERED', 'FLASH_SALE'
        )
    ),
    discount_value DECIMAL(15, 2) NOT NULL,
    max_discount_amount DECIMAL(15, 2),
    min_purchase_amount DECIMAL(15, 2),
    applies_to VARCHAR(20) NOT NULL,  -- ALL, CATEGORY, PRODUCT, SKU
    applicable_ids UUID[],
    excluded_product_ids UUID[],
    total_usage_limit INTEGER,
    used_count INTEGER NOT NULL DEFAULT 0,
    max_uses_per_user INTEGER DEFAULT 1,
    total_budget DECIMAL(15, 2),
    used_budget DECIMAL(15, 2) NOT NULL DEFAULT 0,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    is_stackable BOOLEAN NOT NULL DEFAULT false,
    stacking_group VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INTEGER NOT NULL DEFAULT 1
);
```

### 2.2 Drools Rules Engine (Agent Task: PROMO-DROOLS-001)

**Setup Drools**:
```xml
<dependency>
    <groupId>org.drools</groupId>
    <artifactId>drools-core</artifactId>
    <version>8.44.0.Final</version>
</dependency>
```

**Example Rules** (`promotions.drl`):
```java
package com.example.promotion_service.rules;

import com.example.promotion_service.dto.CartDto;
import com.example.promotion_service.dto.CartItemDto;
import com.example.promotion_service.dto.DiscountDto;
import java.math.BigDecimal;

rule "Buy 2 Get 1 Free Electronics"
    when
        $cart : CartDto()
        $total : Number(intValue >= 3) from accumulate(
            CartItemDto(categoryId == "electronics", $qty : quantity) from $cart.items,
            sum($qty)
        )
    then
        BigDecimal freeItemValue = findCheapestElectronicsPrice($cart);
        insert(new DiscountDto("BUY_2_GET_1_ELECTRONICS", freeItemValue));
end

rule "Tiered Discount Fashion"
    salience 10  // Higher priority
    when
        $cart : CartDto()
        $total : BigDecimal() from accumulate(
            CartItemDto(categoryId == "fashion", $price : totalPrice) from $cart.items,
            sum($price)
        )
        eval($total.compareTo(new BigDecimal("1000000")) >= 0)
    then
        BigDecimal discount = $total.multiply(new BigDecimal("0.15"));  // 15% off
        insert(new DiscountDto("TIERED_FASHION", discount));
end

rule "Free Shipping Over 500k"
    when
        $cart : CartDto(subtotal >= 500000)
    then
        insert(new DiscountDto("FREE_SHIPPING", $cart.getShippingFee()));
end
```

**Drools Service**:
```java
@Service
@RequiredArgsConstructor
public class DroolsPromotionEngine {
    private final KieContainer kieContainer;
    
    public List<DiscountDto> calculateDiscounts(CartDto cart) {
        KieSession kieSession = kieContainer.newKieSession();
        
        try {
            // Insert cart into working memory
            kieSession.insert(cart);
            cart.getItems().forEach(kieSession::insert);
            
            // Fire all rules
            kieSession.fireAllRules();
            
            // Collect discounts
            List<DiscountDto> discounts = new ArrayList<>();
            kieSession.getObjects(new ObjectFilter() {
                @Override
                public boolean accept(Object object) {
                    return object instanceof DiscountDto;
                }
            }).forEach(obj -> discounts.add((DiscountDto) obj));
            
            return discounts;
        } finally {
            kieSession.dispose();
        }
    }
}
```

### 2.3 Promotion Service (Agent Task: PROMO-SERVICE-001)

**Validation & Application**:
```java
@Service
@RequiredArgsConstructor
public class PromotionService {
    private final PromotionRepository promotionRepository;
    private final PromotionUsageRepository usageRepository;
    private final DroolsPromotionEngine droolsEngine;
    private final RedisService redisService;
    
    public ValidatePromotionResponse validatePromotion(String code, UUID userId, CartDto cart) {
        // Check cache
        String cacheKey = "promo:" + code;
        Promotion promo = redisService.get(cacheKey, Promotion.class)
            .orElseGet(() -> {
                Promotion p = promotionRepository.findByCode(code)
                    .orElseThrow(() -> new PromotionNotFoundException());
                cachePromotion(p);
                return p;
            });
        
        // Validate status
        if (promo.getStatus() != PromotionStatus.ACTIVE) {
            return ValidatePromotionResponse.invalid("Promotion not active");
        }
        
        // Validate time window
        Instant now = Instant.now();
        if (now.isBefore(promo.getValidFrom()) || now.isAfter(promo.getValidUntil())) {
            return ValidatePromotionResponse.invalid("Promotion expired");
        }
        
        // Validate total usage limit
        if (promo.getTotalUsageLimit() != null && 
            promo.getUsedCount() >= promo.getTotalUsageLimit()) {
            return ValidatePromotionResponse.invalid("Promotion usage limit reached");
        }
        
        // Validate per-user limit
        int userUsageCount = usageRepository.countByPromotionIdAndUserId(
            promo.getPromotionId(), userId
        );
        if (promo.getMaxUsesPerUser() != null && 
            userUsageCount >= promo.getMaxUsesPerUser()) {
            return ValidatePromotionResponse.invalid("You've reached the usage limit");
        }
        
        // Validate budget
        if (promo.getTotalBudget() != null && 
            promo.getUsedBudget().compareTo(promo.getTotalBudget()) >= 0) {
            return ValidatePromotionResponse.invalid("Promotion budget exhausted");
        }
        
        // Validate min purchase amount
        if (promo.getMinPurchaseAmount() != null && 
            cart.getSubtotal().compareTo(promo.getMinPurchaseAmount()) < 0) {
            return ValidatePromotionResponse.invalid(
                "Minimum purchase: " + promo.getMinPurchaseAmount()
            );
        }
        
        // Calculate discount
        BigDecimal discountAmount = calculateDiscountAmount(promo, cart);
        
        return ValidatePromotionResponse.valid(promo, discountAmount);
    }
    
    public ApplyPromotionResponse applyPromotion(String code, UUID userId, CartDto cart) {
        ValidatePromotionResponse validation = validatePromotion(code, userId, cart);
        
        if (!validation.isValid()) {
            throw new InvalidPromotionException(validation.getReason());
        }
        
        Promotion promo = validation.getPromotion();
        BigDecimal discountAmount = validation.getDiscountAmount();
        
        // Apply to cart items (distribute discount)
        List<CartItemDto> discountedItems = distributeDiscount(promo, cart, discountAmount);
        
        return ApplyPromotionResponse.builder()
            .promotionId(promo.getPromotionId())
            .code(code)
            .discountAmount(discountAmount)
            .items(discountedItems)
            .build();
    }
    
    public List<DiscountDto> calculateAutoPromotions(CartDto cart) {
        // Use Drools to find applicable auto-promotions
        return droolsEngine.calculateDiscounts(cart);
    }
    
    private BigDecimal calculateDiscountAmount(Promotion promo, CartDto cart) {
        BigDecimal discount = BigDecimal.ZERO;
        
        switch (promo.getPromotionType()) {
            case PERCENTAGE:
                discount = cart.getSubtotal()
                    .multiply(promo.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                
                if (promo.getMaxDiscountAmount() != null) {
                    discount = discount.min(promo.getMaxDiscountAmount());
                }
                break;
                
            case FIXED_AMOUNT:
                discount = promo.getDiscountValue();
                break;
                
            // Other types...
        }
        
        return discount;
    }
}
```

### 2.4 Promotion API (Agent Task: PROMO-API-001)

**Endpoints**:
```java
POST   /api/promotions/validate     - Validate coupon code
POST   /api/promotions/apply        - Apply promotion to cart
GET    /api/promotions/available    - List available promotions
GET    /api/promotions/{id}         - Get promotion details

// Admin endpoints
POST   /api/admin/promotions        - Create promotion
PUT    /api/admin/promotions/{id}   - Update promotion
DELETE /api/admin/promotions/{id}   - Delete promotion
GET    /api/admin/promotions/usage  - Usage statistics
```

---

## Phase 3: Order Service (Week 3-4)

### 3.1 Database Schema (Agent Task: DB-ORDER-001)

**Tables**:
- `orders` - Main order
- `order_items` - Line items
- `order_history` - State transitions
- `order_payments` - Payment records
- `order_shipments` - Shipping info

**Order State Machine** (see `workflow-order-service.txt`):
```sql
CREATE TABLE orders (
    order_id UUID PRIMARY KEY,
    order_number VARCHAR(30) NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    previous_status VARCHAR(30),
    status_reason TEXT,
    -- Pricing
    subtotal DECIMAL(15, 2) NOT NULL,
    discount_total DECIMAL(15, 2) NOT NULL DEFAULT 0,
    shipping_fee DECIMAL(15, 2) NOT NULL DEFAULT 0,
    tax_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,
    total_amount DECIMAL(15, 2) NOT NULL,
    -- Addresses
    shipping_address JSONB NOT NULL,
    billing_address JSONB,
    -- Metadata
    notes TEXT,
    internal_notes TEXT,
    -- Timestamps
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    shipped_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);
```

### 3.2 Order State Machine (Agent Task: ORDER-SM-001)

**State Transitions**:
```java
public enum OrderStatus {
    PENDING,                  // Created, awaiting email confirmation
    PENDING_CONFIRMATION,     // Email sent, waiting for code
    PROCESSING,               // Confirmed, saga running
    PAID,                     // Payment successful
    SHIPPED,                  // Shipped from warehouse
    DELIVERED,                // Delivered to customer
    CANCELLED,                // Cancelled before payment
    FAILED,                   // Saga failed (payment/inventory)
    RETURNED,                 // Customer initiated return
    REFUNDED                  // Refund completed
}

@Component
public class OrderStateMachine {
    private static final Map<OrderStatus, List<OrderStatus>> TRANSITIONS = Map.ofEntries(
        entry(PENDING, List.of(PENDING_CONFIRMATION, CANCELLED)),
        entry(PENDING_CONFIRMATION, List.of(PROCESSING, CANCELLED)),
        entry(PROCESSING, List.of(PAID, FAILED, CANCELLED)),
        entry(PAID, List.of(SHIPPED, CANCELLED, RETURNED)),
        entry(SHIPPED, List.of(DELIVERED, RETURNED)),
        entry(DELIVERED, List.of(RETURNED)),
        entry(CANCELLED, List.of(REFUNDED)),
        entry(FAILED, List.of(CANCELLED)),
        entry(RETURNED, List.of(REFUNDED))
    );
    
    public boolean canTransition(OrderStatus from, OrderStatus to) {
        return TRANSITIONS.getOrDefault(from, Collections.emptyList()).contains(to);
    }
    
    public void transition(Order order, OrderStatus newStatus, String reason) {
        if (!canTransition(order.getStatus(), newStatus)) {
            throw new IllegalStateTransitionException(
                String.format("Cannot transition from %s to %s", 
                    order.getStatus(), newStatus)
            );
        }
        
        order.setPreviousStatus(order.getStatus());
        order.setStatus(newStatus);
        order.setStatusReason(reason);
        
        // Record in history
        recordStateTransition(order, newStatus, reason);
        
        // Publish event
        publishStateChangedEvent(order);
    }
}
```

### 3.3 Temporal Saga Workflow (Agent Task: ORDER-SAGA-001)

**Workflow Definition**:
```java
@WorkflowInterface
public interface OrderSagaWorkflow {
    @WorkflowMethod
    OrderSagaResult processOrder(OrderSagaRequest request);
}

@Workflow
public class OrderSagaWorkflowImpl implements OrderSagaWorkflow {
    
    private final PaymentActivities paymentActivities = 
        Workflow.newActivityStub(PaymentActivities.class);
    
    private final InventoryActivities inventoryActivities = 
        Workflow.newActivityStub(InventoryActivities.class);
    
    private final ShippingActivities shippingActivities = 
        Workflow.newActivityStub(ShippingActivities.class);
    
    @Override
    public OrderSagaResult processOrder(OrderSagaRequest request) {
        OrderSagaResult result = new OrderSagaResult();
        result.setOrderId(request.getOrderId());
        
        try {
            // Step 1: Reserve inventory
            InventoryReservationResult inventoryResult = 
                inventoryActivities.reserveStock(request.getItems());
            
            if (!inventoryResult.isSuccess()) {
                return result.failed("Insufficient stock: " + inventoryResult.getReason());
            }
            result.setInventoryReservationId(inventoryResult.getReservationId());
            
            // Step 2: Process payment
            PaymentResult paymentResult = 
                paymentActivities.processPayment(
                    request.getOrderId(),
                    request.getTotalAmount(),
                    request.getPaymentMethod()
                );
            
            if (!paymentResult.isSuccess()) {
                // Compensate: Release inventory
                inventoryActivities.releaseReservation(result.getInventoryReservationId());
                return result.failed("Payment failed: " + paymentResult.getReason());
            }
            result.setPaymentId(paymentResult.getPaymentId());
            
            // Step 3: Confirm inventory
            inventoryActivities.confirmReservation(result.getInventoryReservationId());
            
            // Step 4: Create shipment
            ShipmentResult shipmentResult = 
                shippingActivities.createShipment(
                    request.getOrderId(),
                    request.getShippingAddress(),
                    request.getItems()
                );
            result.setShipmentId(shipmentResult.getShipmentId());
            
            return result.success();
            
        } catch (Exception e) {
            // Compensate all
            compensateAll(result);
            return result.failed("Saga failed: " + e.getMessage());
        }
    }
    
    private void compensateAll(OrderSagaResult result) {
        if (result.getPaymentId() != null) {
            try {
                paymentActivities.refundPayment(result.getPaymentId());
            } catch (Exception e) {
                // Log compensation failure
            }
        }
        
        if (result.getInventoryReservationId() != null) {
            try {
                inventoryActivities.releaseReservation(result.getInventoryReservationId());
            } catch (Exception e) {
                // Log compensation failure
            }
        }
    }
}
```

### 3.4 Order Service (Agent Task: ORDER-SERVICE-001)

**Core Methods**:
```java
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {
    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final OrderStateMachine stateMachine;
    private final WorkflowClient temporalClient;
    private final OTPService otpService;
    private final OrderEventPublisher eventPublisher;
    
    public CreateOrderResponse createOrder(CreateOrderRequest request, UUID userId) {
        // 1. Validate cart
        CartDto cart = cartService.getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException();
        }
        
        // 2. Validate selected items
        List<CartItemDto> selectedItems = cart.getItems().stream()
            .filter(CartItemDto::isSelected)
            .collect(Collectors.toList());
        
        if (selectedItems.isEmpty()) {
            throw new NoItemsSelectedException();
        }
        
        // 3. Validate stock availability
        selectedItems.forEach(item -> {
            if (item.getStockStatus() == StockStatus.OUT_OF_STOCK) {
                throw new OutOfStockException(item.getProductName());
            }
        });
        
        // 4. Calculate totals
        OrderTotals totals = calculateTotals(selectedItems, request.getShippingMethodId());
        
        // 5. Create order entity
        Order order = Order.builder()
            .orderId(UUID.randomUUID())
            .orderNumber(generateOrderNumber())
            .userId(userId)
            .status(OrderStatus.PENDING)
            .subtotal(totals.getSubtotal())
            .discountTotal(totals.getDiscountTotal())
            .shippingFee(totals.getShippingFee())
            .taxAmount(totals.getTaxAmount())
            .totalAmount(totals.getTotalAmount())
            .shippingAddress(toJson(request.getShippingAddress()))
            .billingAddress(toJson(request.getBillingAddress()))
            .notes(request.getNotes())
            .createdAt(Instant.now())
            .build();
        
        // 6. Add order items
        selectedItems.forEach(item -> {
            OrderItem orderItem = OrderItem.builder()
                .orderItemId(UUID.randomUUID())
                .orderId(order.getOrderId())
                .productId(item.getProductId())
                .skuId(item.getSkuId())
                .sellerId(item.getSellerId())
                .productName(item.getProductName())
                .variantInfo(item.getVariantInfo())
                .quantity(item.getQuantity())
                .unitPrice(item.getCurrentUnitPrice())
                .originalPrice(item.getOriginalPrice())
                .discountAmount(item.getDiscountAmount())
                .subtotal(item.getTotalPrice())
                .build();
            
            order.addItem(orderItem);
        });
        
        orderRepository.save(order);
        
        // 7. Send confirmation email with OTP
        String requestId = otpService.sendEmailOTP(
            request.getEmail(),
            OTPPurpose.ORDER_CONFIRMATION
        );
        
        // 8. Publish OrderCreatedEvent
        eventPublisher.publish(OrderCreatedEvent.builder()
            .orderId(order.getOrderId().toString())
            .userId(userId.toString())
            .orderNumber(order.getOrderNumber())
            .totalAmount(order.getTotalAmount())
            .status(OrderStatus.PENDING.name())
            .build());
        
        // 9. Mark cart items as converted
        cartService.convertToOrder(cart.getCartId(), order.getOrderId());
        
        return CreateOrderResponse.builder()
            .orderId(order.getOrderId())
            .orderNumber(order.getOrderNumber())
            .otpRequestId(requestId)
            .expiresIn(900) // 15 minutes
            .build();
    }
    
    public void confirmOrder(UUID orderId, String otpCode) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException());
        
        // Validate OTP
        boolean isValid = otpService.verifyOTP(
            otpCode,
            OTPPurpose.ORDER_CONFIRMATION,
            order.getUserEmail()
        );
        
        if (!isValid) {
            throw new InvalidOTPException();
        }
        
        // Transition to PROCESSING
        stateMachine.transition(order, OrderStatus.PROCESSING, "OTP verified");
        order.setConfirmedAt(Instant.now());
        orderRepository.save(order);
        
        // Start Temporal saga
        OrderSagaRequest sagaRequest = OrderSagaRequest.builder()
            .orderId(order.getOrderId().toString())
            .userId(order.getUserId().toString())
            .items(mapOrderItemsToSagaItems(order.getItems()))
            .totalAmount(order.getTotalAmount())
            .paymentMethod(order.getPaymentMethod())
            .shippingAddress(order.getShippingAddress())
            .build();
        
        OrderSagaWorkflow workflow = temporalClient.newWorkflowStub(
            OrderSagaWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId("order-saga-" + order.getOrderId())
                .setTaskQueue("order-saga")
                .build()
        );
        
        WorkflowClient.start(workflow::processOrder, sagaRequest);
    }
    
    @Async
    public void handleSagaResult(String orderId, OrderSagaResult result) {
        Order order = orderRepository.findById(UUID.fromString(orderId))
            .orElseThrow(() -> new OrderNotFoundException());
        
        if (result.isSuccess()) {
            stateMachine.transition(order, OrderStatus.PAID, "Payment successful");
            order.setPaidAt(Instant.now());
        } else {
            stateMachine.transition(order, OrderStatus.FAILED, result.getReason());
        }
        
        orderRepository.save(order);
    }
}
```

### 3.5 Order API (Agent Task: ORDER-API-001)

**Endpoints**:
```java
POST   /api/orders                  - Create order
POST   /api/orders/{id}/confirm     - Confirm order (OTP)
GET    /api/orders/{id}             - Get order details
GET    /api/orders                  - List user orders
POST   /api/orders/{id}/cancel      - Cancel order
POST   /api/orders/{id}/return      - Request return
GET    /api/orders/{id}/tracking    - Get tracking info

// Admin endpoints
PUT    /api/admin/orders/{id}/status  - Update order status
GET    /api/admin/orders               - List all orders
GET    /api/admin/orders/stats         - Order statistics
```

---

## Phase 4: Integration & Testing (Week 5)

### 4.1 Inter-Service Integration (Agent Task: INT-SHOPPING-001)

**Event Listeners**:
```java
@Component
@RequiredArgsConstructor
public class ShoppingEventHandler {
    
    @KafkaListener(topics = "product-events", groupId = "cart-service-group")
    public void handleProductEvent(@Payload BaseEvent event) {
        if (event instanceof ProductPriceChangedEvent) {
            // Update cart item prices
            updateCartItemPrices((ProductPriceChangedEvent) event);
        } else if (event instanceof ProductStockUpdatedEvent) {
            // Update cart item stock status
            updateCartItemStock((ProductStockUpdatedEvent) event);
        }
    }
    
    @KafkaListener(topics = "payment-events", groupId = "order-service-group")
    public void handlePaymentEvent(@Payload BaseEvent event) {
        if (event instanceof PaymentSuccessEvent) {
            orderService.handlePaymentSuccess((PaymentSuccessEvent) event);
        } else if (event instanceof PaymentFailedEvent) {
            orderService.handlePaymentFailure((PaymentFailedEvent) event);
        }
    }
    
    @KafkaListener(topics = "shipping-events", groupId = "order-service-group")
    public void handleShippingEvent(@Payload BaseEvent event) {
        if (event instanceof ShipmentCreatedEvent) {
            orderService.updateShippingInfo((ShipmentCreatedEvent) event);
        } else if (event instanceof ShipmentDeliveredEvent) {
            orderService.markAsDelivered((ShipmentDeliveredEvent) event);
        }
    }
}
```

### 4.2 End-to-End Tests (Agent Task: TEST-SHOPPING-E2E-001)

**Test Scenarios**:
```java
@SpringBootTest
@AutoConfigureMockMvc
class OrderFlowIntegrationTest {
    
    @Test
    void testCompleteOrderFlow() {
        // 1. Add items to cart
        addToCart(productId1, skuId1, 2);
        addToCart(productId2, skuId2, 1);
        
        // 2. Apply coupon
        applyCoupon("SAVE10");
        
        // 3. Create order
        CreateOrderResponse orderResponse = createOrder(shippingAddress);
        assertNotNull(orderResponse.getOrderId());
        assertEquals(OrderStatus.PENDING, getOrderStatus(orderResponse.getOrderId()));
        
        // 4. Confirm with OTP
        confirmOrder(orderResponse.getOrderId(), "123456");
        assertEquals(OrderStatus.PROCESSING, getOrderStatus(orderResponse.getOrderId()));
        
        // 5. Wait for saga completion
        await().atMost(30, SECONDS).until(() -> 
            getOrderStatus(orderResponse.getOrderId()) == OrderStatus.PAID
        );
        
        // 6. Verify inventory reserved
        verifyInventoryReserved(productId1, skuId1, 2);
        
        // 7. Verify payment recorded
        verifyPaymentRecorded(orderResponse.getOrderId());
        
        // 8. Verify shipment created
        verifyShipmentCreated(orderResponse.getOrderId());
    }
    
    @Test
    void testOrderCancellation() {
        // Create and confirm order
        UUID orderId = createAndConfirmOrder();
        
        // Cancel before payment
        cancelOrder(orderId);
        
        // Verify status
        assertEquals(OrderStatus.CANCELLED, getOrderStatus(orderId));
        
        // Verify inventory released
        verifyInventoryReleased();
    }
    
    @Test
    void testSagaCompensation() {
        // Mock payment failure
        mockPaymentFailure();
        
        // Create and confirm order
        UUID orderId = createAndConfirmOrder();
        
        // Wait for saga completion
        await().atMost(30, SECONDS).until(() -> 
            getOrderStatus(orderId) == OrderStatus.FAILED
        );
        
        // Verify inventory released (compensation)
        verifyInventoryReleased();
    }
}
```

---

## AI Agent Task Breakdown

### Week 1: Cart Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-CART-001 | Database schema | 4h | - |
| CART-AGG-001 | Cart aggregate | 8h | DB-CART-001 |
| CART-SERVICE-001 | Service layer | 10h | CART-AGG-001 |
| CART-API-001 | REST API | 6h | CART-SERVICE-001 |

### Week 2: Promotion Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-PROMO-001 | Database schema | 4h | - |
| PROMO-DROOLS-001 | Drools setup | 8h | DB-PROMO-001 |
| PROMO-SERVICE-001 | Service layer | 12h | PROMO-DROOLS-001 |
| PROMO-API-001 | REST API | 6h | PROMO-SERVICE-001 |

### Week 3-4: Order Service
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| DB-ORDER-001 | Database schema | 6h | - |
| ORDER-SM-001 | State machine | 8h | DB-ORDER-001 |
| ORDER-SAGA-001 | Temporal saga | 12h | ORDER-SM-001 |
| ORDER-SERVICE-001 | Service layer | 14h | ORDER-SAGA-001 |
| ORDER-API-001 | REST API | 8h | ORDER-SERVICE-001 |

### Week 5: Integration & Testing
| Task ID | Description | Time | Dependencies |
|---------|-------------|------|--------------|
| INT-SHOPPING-001 | Event integration | 8h | All services |
| TEST-SHOPPING-E2E-001 | E2E tests | 16h | All services |

---

## Validation Checklist

### Cart Service
- [ ] Can add/remove items
- [ ] Quantity updates work
- [ ] Price snapshots captured
- [ ] Guest cart creation
- [ ] Guest→User merge functional
- [ ] Cart expiry mechanism
- [ ] Cache invalidation works

### Promotion Service
- [ ] Coupon validation works
- [ ] Drools rules execute
- [ ] Auto-promotions applied
- [ ] Stacking rules enforced
- [ ] Usage limits respected
- [ ] Budget tracking accurate

### Order Service
- [ ] Order creation with OTP
- [ ] State machine validates transitions
- [ ] Temporal saga runs successfully
- [ ] Payment compensation works
- [ ] Inventory released on cancel
- [ ] Shipment tracking integrated
- [ ] Order history recorded

### Integration
- [ ] Cart prices update on product changes
- [ ] Orders update on payment events
- [ ] Saga completes in < 30s
- [ ] All compensations execute
- [ ] No data loss on failures

---

**End of Shopping Services Implementation Plan**
