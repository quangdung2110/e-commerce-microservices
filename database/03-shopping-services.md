# Group 3: Shopping Experience Services

> **Services**: Cart Service, Promotion Service, Order Service
> **Pattern**: Event Sourcing + CQRS + Saga (Temporal)
> **Database**: PostgreSQL

---

## Table of Contents
1. [Cart Service](#1-cart-service)
2. [Promotion Service](#2-promotion-service)
3. [Order Service](#3-order-service)
4. [Cross-Service Integration](#4-cross-service-integration)
5. [Kafka Topics & Redis Caching](#5-kafka-topics--redis-caching)

---

## 1. Cart Service

### 1.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing `session_id` for guest carts | HIGH | Add `session_id` for anonymous users |
| 2 | Missing `seller_id` in items | HIGH | Required for grouping by seller (shipping) |
| 3 | No price snapshot at add time | HIGH | Add `unit_price_at_add` for price protection |
| 4 | Missing `expires_at` for guest carts | MEDIUM | Auto-cleanup guest carts after 30 days |
| 5 | Missing `selected` flag per item | MEDIUM | User can unselect items for checkout |
| 6 | No warehouse preference | MEDIUM | Add `preferred_warehouse_id` |
| 7 | Missing `promotion_discount` per item | MEDIUM | Track item-level discounts |
| 8 | No `stock_status` denormalized | MEDIUM | Quick availability display |
| 9 | Missing saved for later functionality | LOW | Add `saved_items` table |
| 10 | No merge logic for guest→user | HIGH | Track `merged_from_session` |

### 1.2 Database Schema

#### Table: `carts`
```sql
CREATE TABLE carts (
    cart_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Ownership (one of these is required)
    user_id             UUID,  -- NULL for guest
    session_id          VARCHAR(100),  -- For guest users
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        status IN ('ACTIVE', 'MERGED', 'CONVERTED', 'ABANDONED', 'EXPIRED')
    ),
    
    -- Totals (denormalized)
    item_count          INTEGER NOT NULL DEFAULT 0,
    subtotal            DECIMAL(15, 2) NOT NULL DEFAULT 0,
    discount_total      DECIMAL(15, 2) NOT NULL DEFAULT 0,
    
    -- Applied promotions
    applied_promotion_ids UUID[],
    coupon_code         VARCHAR(50),
    
    -- Lifecycle
    merged_from_session VARCHAR(100),  -- If merged from guest cart
    converted_to_order  UUID,  -- Order ID if converted
    
    -- Expiry
    expires_at          TIMESTAMPTZ,  -- For guest carts
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1,
    
    CONSTRAINT chk_cart_owner CHECK (user_id IS NOT NULL OR session_id IS NOT NULL)
);

CREATE UNIQUE INDEX idx_carts_user ON carts(user_id) WHERE user_id IS NOT NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX idx_carts_session ON carts(session_id) WHERE session_id IS NOT NULL AND status = 'ACTIVE';
CREATE INDEX idx_carts_expires ON carts(expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
CREATE INDEX idx_carts_abandoned ON carts(updated_at) WHERE status = 'ACTIVE';
```

#### Table: `cart_items`
```sql
CREATE TABLE cart_items (
    item_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id             UUID NOT NULL REFERENCES carts(cart_id) ON DELETE CASCADE,
    
    -- Product reference
    product_id          UUID NOT NULL,
    sku_id              UUID NOT NULL,
    seller_id           UUID NOT NULL,
    
    -- Quantity
    quantity            INTEGER NOT NULL CHECK (quantity > 0),
    
    -- Price snapshot
    unit_price_at_add   DECIMAL(15, 2) NOT NULL,  -- Price when added
    current_unit_price  DECIMAL(15, 2) NOT NULL,  -- Latest price
    original_price      DECIMAL(15, 2),  -- Before any discount
    
    -- Item-level discount
    discount_amount     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    discount_reason     VARCHAR(100),  -- Promotion name, etc.
    
    -- Selection
    is_selected         BOOLEAN NOT NULL DEFAULT true,
    
    -- Stock status (denormalized)
    stock_status        VARCHAR(20) NOT NULL DEFAULT 'IN_STOCK' CHECK (
        stock_status IN ('IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK', 'PREORDER')
    ),
    available_quantity  INTEGER,
    
    -- Fulfillment preference
    preferred_warehouse_id UUID,
    
    -- Product snapshot (for display)
    product_name        VARCHAR(500) NOT NULL,
    variant_info        VARCHAR(200),  -- "Đỏ, Size L"
    image_url           TEXT,
    
    -- Audit
    added_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_cart_item UNIQUE (cart_id, sku_id)
);

CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);
CREATE INDEX idx_cart_items_seller ON cart_items(cart_id, seller_id);
CREATE INDEX idx_cart_items_product ON cart_items(product_id);
```

#### Table: `saved_items`
```sql
-- "Save for later" functionality
CREATE TABLE saved_items (
    saved_item_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    
    -- Product reference
    product_id          UUID NOT NULL,
    sku_id              UUID NOT NULL,
    
    -- Snapshot
    product_name        VARCHAR(500) NOT NULL,
    variant_info        VARCHAR(200),
    image_url           TEXT,
    price_at_save       DECIMAL(15, 2) NOT NULL,
    
    -- Audit
    saved_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_saved_item UNIQUE (user_id, sku_id)
);

CREATE INDEX idx_saved_items_user ON saved_items(user_id, saved_at DESC);
```

### 1.3 Event Sourcing

#### Domain Events
```java
@SuperBuilder
public abstract class CartEvent extends BaseEvent {
    protected String cartId;
    protected String userId;
    protected String sessionId;
}

@DomainEvent(aggregateType = "Cart")
public class CartCreatedEvent extends CartEvent {
    private CartType cartType;  // USER, GUEST
}

@DomainEvent(aggregateType = "Cart")
public class ItemAddedToCartEvent extends CartEvent {
    private String itemId;
    private String productId;
    private String skuId;
    private String sellerId;
    private int quantity;
    private BigDecimal unitPrice;
    private String productName;
    private String variantInfo;
}

@DomainEvent(aggregateType = "Cart")
public class ItemQuantityUpdatedEvent extends CartEvent {
    private String itemId;
    private int previousQuantity;
    private int newQuantity;
}

@DomainEvent(aggregateType = "Cart")
public class ItemRemovedFromCartEvent extends CartEvent {
    private String itemId;
    private String reason;  // USER_REMOVED, OUT_OF_STOCK, PRODUCT_UNAVAILABLE
}

@DomainEvent(aggregateType = "Cart")
public class ItemSelectionChangedEvent extends CartEvent {
    private String itemId;
    private boolean selected;
}

@DomainEvent(aggregateType = "Cart")
public class CartMergedEvent extends CartEvent {
    private String sourceSessionId;
    private List<MergedItemDto> mergedItems;
    private List<String> conflictedSkus;  // Same SKU, summed quantities
}

@DomainEvent(aggregateType = "Cart")
public class PromotionAppliedEvent extends CartEvent {
    private String promotionId;
    private String couponCode;
    private BigDecimal discountAmount;
    private List<ItemDiscountDto> itemDiscounts;
}

@DomainEvent(aggregateType = "Cart")
public class CartConvertedToOrderEvent extends CartEvent {
    private String orderId;
    private List<String> convertedItemIds;
}

@DomainEvent(aggregateType = "Cart")
public class CartExpiredEvent extends CartEvent {
    private Instant expiredAt;
}
```

#### Aggregate
```java
@Aggregate
public class CartAggregate {
    private String cartId;
    private String userId;
    private String sessionId;
    private String status;
    private Map<String, CartItem> items = new HashMap<>();
    private List<String> appliedPromotionIds = new ArrayList<>();
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal discountTotal = BigDecimal.ZERO;
    
    public void addItem(AddItemCommand cmd) {
        // Check if item already exists
        Optional<CartItem> existing = items.values().stream()
            .filter(item -> item.getSkuId().equals(cmd.getSkuId()))
            .findFirst();
        
        if (existing.isPresent()) {
            // Update quantity instead
            updateItemQuantity(existing.get().getItemId(), 
                existing.get().getQuantity() + cmd.getQuantity());
            return;
        }
        
        // Validate max items per cart
        if (items.size() >= 100) {
            throw new CartLimitExceededException("Maximum 100 items per cart");
        }
        
        apply(ItemAddedToCartEvent.builder()
            .aggregateId(cartId)
            .cartId(cartId)
            .userId(userId)
            .sessionId(sessionId)
            .itemId(UUID.randomUUID().toString())
            .productId(cmd.getProductId())
            .skuId(cmd.getSkuId())
            .sellerId(cmd.getSellerId())
            .quantity(cmd.getQuantity())
            .unitPrice(cmd.getUnitPrice())
            .productName(cmd.getProductName())
            .variantInfo(cmd.getVariantInfo())
            .build());
        
        recalculateTotals();
    }
    
    public void merge(String sourceSessionId, List<CartItem> guestItems) {
        List<MergedItemDto> merged = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        
        for (CartItem guestItem : guestItems) {
            Optional<CartItem> existing = items.values().stream()
                .filter(item -> item.getSkuId().equals(guestItem.getSkuId()))
                .findFirst();
            
            if (existing.isPresent()) {
                // Merge quantities
                conflicted.add(guestItem.getSkuId());
                int newQty = Math.min(existing.get().getQuantity() + guestItem.getQuantity(), 99);
                updateItemQuantity(existing.get().getItemId(), newQty);
            } else {
                // Add new item
                addItem(/* converted from guestItem */);
            }
            merged.add(/* ... */);
        }
        
        apply(CartMergedEvent.builder()
            .aggregateId(cartId)
            .cartId(cartId)
            .userId(userId)
            .sourceSessionId(sourceSessionId)
            .mergedItems(merged)
            .conflictedSkus(conflicted)
            .build());
    }
}
```

### 1.4 Workflow: Cart Merge on Login

```
┌───────────────────────────────────────────────────────────────────────┐
│                    Guest → User Cart Merge                            │
└───────────────────────────────────────────────────────────────────────┘

Guest (session=abc123)                 User logs in (user=U1)
┌─────────────────┐                    ┌─────────────────┐
│   Guest Cart    │                    │   User Cart     │
│   session=abc   │                    │   user=U1       │
├─────────────────┤                    ├─────────────────┤
│ SKU-A x 2       │                    │ SKU-A x 1       │
│ SKU-B x 1       │                    │ SKU-C x 3       │
└────────┬────────┘                    └────────┬────────┘
         │                                      │
         │         ┌──────────────┐             │
         └────────▶│ Merge Logic  │◀────────────┘
                   └──────┬───────┘
                          │
                          ▼
                   ┌─────────────────┐
                   │  Merged Cart    │
                   │  user=U1        │
                   ├─────────────────┤
                   │ SKU-A x 3 ⚠️    │ ← Summed
                   │ SKU-B x 1       │ ← From guest
                   │ SKU-C x 3       │ ← Existing
                   └─────────────────┘

Events:
1. CartMergedEvent { sourceSession="abc123", mergedItems=[...], conflicts=["SKU-A"] }
2. Guest cart status → MERGED
```

---

## 2. Promotion Service

### 2.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing stacking rules | HIGH | Add `promotion_stacking_rules` table |
| 2 | No budget tracking | HIGH | Add `total_budget`, `used_budget` |
| 3 | Missing per-user limit | HIGH | Add `max_uses_per_user` and tracking |
| 4 | No tier-based discounts | MEDIUM | Add `promotion_tiers` for volume discounts |
| 5 | Missing seller promotions | MEDIUM | Add `seller_id` for seller-specific promos |
| 6 | No exclusion rules | MEDIUM | Add `excluded_product_ids`, `excluded_category_ids` |
| 7 | Missing time-based triggers | MEDIUM | Support Flash Sale time windows |
| 8 | No AB testing support | LOW | Add `experiment_id` for variants |
| 9 | Missing promotion groups | LOW | Group related promotions |
| 10 | No real-time validation caching | MEDIUM | Redis-based quick validation |

### 2.2 Database Schema

#### Table: `promotions`
```sql
CREATE TABLE promotions (
    promotion_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Ownership
    seller_id           UUID,  -- NULL = platform-wide
    created_by          UUID NOT NULL,
    
    -- Basic info
    name                VARCHAR(200) NOT NULL,
    code                VARCHAR(50),  -- Coupon code (optional)
    description         TEXT,
    
    -- Type
    promotion_type      VARCHAR(30) NOT NULL CHECK (
        promotion_type IN (
            'PERCENTAGE',      -- % off
            'FIXED_AMOUNT',    -- Fixed discount
            'BUY_X_GET_Y',     -- Buy X get Y free/discounted
            'FREE_SHIPPING',   -- Free shipping
            'BUNDLE',          -- Bundle discount
            'TIERED',          -- Volume discount
            'FLASH_SALE'       -- Time-limited
        )
    ),
    
    -- Discount value
    discount_value      DECIMAL(15, 2) NOT NULL,  -- Percentage or fixed amount
    max_discount_amount DECIMAL(15, 2),  -- Cap for percentage discounts
    
    -- Conditions
    min_purchase_amount DECIMAL(15, 2),
    min_quantity        INTEGER,
    
    -- Applicability
    applies_to          VARCHAR(20) NOT NULL DEFAULT 'ALL' CHECK (
        applies_to IN ('ALL', 'CATEGORY', 'PRODUCT', 'SKU', 'SELLER', 'BRAND')
    ),
    applicable_ids      UUID[],  -- IDs based on applies_to
    
    -- Exclusions
    excluded_product_ids    UUID[],
    excluded_category_ids   UUID[],
    excluded_seller_ids     UUID[],
    
    -- Usage limits
    total_usage_limit   INTEGER,
    used_count          INTEGER NOT NULL DEFAULT 0,
    max_uses_per_user   INTEGER DEFAULT 1,
    
    -- Budget
    total_budget        DECIMAL(15, 2),
    used_budget         DECIMAL(15, 2) NOT NULL DEFAULT 0,
    
    -- Validity
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL,
    
    -- Flash sale specific
    flash_start_time    TIME,  -- Daily start time
    flash_end_time      TIME,  -- Daily end time
    flash_days          INTEGER[],  -- Days of week (1-7)
    
    -- Priority & Stacking
    priority            INTEGER NOT NULL DEFAULT 0,
    is_stackable        BOOLEAN NOT NULL DEFAULT false,
    stacking_group      VARCHAR(50),  -- Promotions in same group can stack
    
    -- Status
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (
        status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'EXPIRED', 'CANCELLED')
    ),
    
    -- AB Testing
    experiment_id       UUID,
    variant             VARCHAR(10),  -- 'A', 'B', 'C'
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_promotions_code ON promotions(code) WHERE code IS NOT NULL;
CREATE INDEX idx_promotions_active ON promotions(status, valid_from, valid_until) 
    WHERE status IN ('SCHEDULED', 'ACTIVE');
CREATE INDEX idx_promotions_seller ON promotions(seller_id) WHERE seller_id IS NOT NULL;
CREATE INDEX idx_promotions_type ON promotions(promotion_type, status);
CREATE INDEX idx_promotions_applies ON promotions USING gin(applicable_ids);
```

#### Table: `promotion_tiers`
```sql
-- Volume/tiered discounts
CREATE TABLE promotion_tiers (
    tier_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id        UUID NOT NULL REFERENCES promotions(promotion_id) ON DELETE CASCADE,
    
    -- Tier definition
    min_quantity        INTEGER,
    min_amount          DECIMAL(15, 2),
    
    -- Discount
    discount_type       VARCHAR(20) NOT NULL CHECK (
        discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT', 'FIXED_PRICE')
    ),
    discount_value      DECIMAL(15, 2) NOT NULL,
    
    -- Order
    tier_order          SMALLINT NOT NULL DEFAULT 0,
    
    CONSTRAINT uq_tier_order UNIQUE (promotion_id, tier_order)
);

CREATE INDEX idx_promo_tiers_promo ON promotion_tiers(promotion_id);
```

#### Table: `promotion_usage`
```sql
-- Track usage per user
CREATE TABLE promotion_usage (
    usage_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id        UUID NOT NULL REFERENCES promotions(promotion_id),
    user_id             UUID NOT NULL,
    order_id            UUID NOT NULL,
    
    -- Usage details
    discount_amount     DECIMAL(15, 2) NOT NULL,
    
    -- Timestamp
    used_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_promo_order UNIQUE (promotion_id, order_id)
);

CREATE INDEX idx_promo_usage_user ON promotion_usage(promotion_id, user_id);
CREATE INDEX idx_promo_usage_time ON promotion_usage(used_at DESC);
```

#### Table: `coupons`
```sql
-- Individual coupon codes (for unique/personalized codes)
CREATE TABLE coupons (
    coupon_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id        UUID NOT NULL REFERENCES promotions(promotion_id),
    
    -- Code
    code                VARCHAR(50) NOT NULL UNIQUE,
    
    -- Assignment
    assigned_to_user    UUID,  -- If personalized
    
    -- Usage
    is_used             BOOLEAN NOT NULL DEFAULT false,
    used_by             UUID,
    used_at             TIMESTAMPTZ,
    used_order_id       UUID,
    
    -- Validity
    valid_from          TIMESTAMPTZ,
    valid_until         TIMESTAMPTZ,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coupons_code ON coupons(code);
CREATE INDEX idx_coupons_promo ON coupons(promotion_id);
CREATE INDEX idx_coupons_user ON coupons(assigned_to_user) WHERE assigned_to_user IS NOT NULL;
```

#### Table: `promotion_stacking_rules`
```sql
-- Rules for which promotions can stack
CREATE TABLE promotion_stacking_rules (
    rule_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Rule definition
    stacking_group      VARCHAR(50) NOT NULL,
    max_stackable       INTEGER NOT NULL DEFAULT 1,  -- Max promos from this group
    
    -- Priority (lower = applied first)
    apply_order         INTEGER NOT NULL DEFAULT 0,
    
    -- Mutual exclusions
    excludes_groups     VARCHAR(50)[],  -- Cannot stack with these groups
    
    -- Status
    is_active           BOOLEAN NOT NULL DEFAULT true
);

CREATE UNIQUE INDEX idx_stacking_group ON promotion_stacking_rules(stacking_group);
```

### 2.3 Drools Rules Engine Integration

```java
// Drools rule example for complex promotions
rule "Buy 2 Get 1 Free - Electronics"
when
    $cart : Cart()
    $items : List() from accumulate(
        CartItem(categoryId == "electronics", $qty : quantity) from $cart.items,
        sum($qty)
    )
    eval($items >= 3)
then
    BigDecimal freeItemValue = findCheapestItemPrice($cart, "electronics");
    applyDiscount($cart, freeItemValue, "BUY_2_GET_1_ELECTRONICS");
end

rule "Tiered Discount - Fashion"
when
    $cart : Cart()
    $total : BigDecimal() from accumulate(
        CartItem(categoryId == "fashion", $price : totalPrice) from $cart.items,
        sum($price)
    )
then
    BigDecimal discount = calculateTieredDiscount($total);
    applyDiscount($cart, discount, "TIERED_FASHION");
end
```

### 2.4 Event Sourcing

#### Domain Events
```java
@DomainEvent(aggregateType = "Promotion")
public class PromotionCreatedEvent extends BaseEvent {
    private String promotionId;
    private String name;
    private String promotionType;
    private BigDecimal discountValue;
    private Instant validFrom;
    private Instant validUntil;
}

@DomainEvent(aggregateType = "Promotion")
public class PromotionActivatedEvent extends BaseEvent {
    private String promotionId;
}

@DomainEvent(aggregateType = "Promotion")
public class PromotionUsedEvent extends BaseEvent {
    private String promotionId;
    private String userId;
    private String orderId;
    private BigDecimal discountAmount;
    private int newUsedCount;
    private BigDecimal newUsedBudget;
}

@DomainEvent(aggregateType = "Promotion")
public class PromotionExhaustedEvent extends BaseEvent {
    private String promotionId;
    private String reason;  // USAGE_LIMIT, BUDGET_LIMIT
}
```

---

## 3. Order Service

### 3.1 Issues Found in User's Design

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | Missing email confirmation flow | HIGH | Add `PENDING_CONFIRMATION` status |
| 2 | No `seller_id` per item | HIGH | Required for multi-seller orders |
| 3 | Missing `sub_orders` for seller splitting | HIGH | Add sub-order structure |
| 4 | No price/discount audit trail | HIGH | Add `price_at_order`, `discount_breakdown` |
| 5 | Missing cancellation reason codes | MEDIUM | Add structured cancellation reasons |
| 6 | No partial cancellation support | MEDIUM | Track cancellation per item |
| 7 | Missing delivery window | MEDIUM | Add `estimated_delivery_from/to` |
| 8 | No payment method snapshot | MEDIUM | Store payment method details |
| 9 | Missing order source tracking | MEDIUM | Add `order_source` (WEB, APP, API) |
| 10 | No support for pre-orders | LOW | Add `is_preorder`, `expected_ship_date` |

### 3.2 Database Schema

#### Table: `orders`
```sql
CREATE TABLE orders (
    order_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Order number (human readable)
    order_number        VARCHAR(30) NOT NULL UNIQUE,
    
    -- Customer
    user_id             UUID NOT NULL,
    
    -- Status
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING',              -- Just created
            'PENDING_CONFIRMATION', -- Waiting email OTP
            'CONFIRMED',            -- Email confirmed
            'PROCESSING',           -- Payment processing
            'PAID',                 -- Payment successful
            'PARTIALLY_SHIPPED',    -- Some items shipped
            'SHIPPED',              -- All items shipped
            'DELIVERED',            -- Delivered to customer
            'COMPLETED',            -- Order finalized
            'CANCELLED',            -- Cancelled before ship
            'FAILED',               -- Payment/processing failed
            'RETURNED',             -- Items returned
            'REFUNDED'              -- Money refunded
        )
    ),
    
    -- Source
    order_source        VARCHAR(20) NOT NULL DEFAULT 'WEB' CHECK (
        order_source IN ('WEB', 'MOBILE_APP', 'API', 'POS', 'MARKETPLACE')
    ),
    device_info         JSONB,
    
    -- Amounts
    subtotal            DECIMAL(15, 2) NOT NULL,
    discount_total      DECIMAL(15, 2) NOT NULL DEFAULT 0,
    shipping_total      DECIMAL(15, 2) NOT NULL DEFAULT 0,
    tax_total           DECIMAL(15, 2) NOT NULL DEFAULT 0,
    grand_total         DECIMAL(15, 2) NOT NULL,
    
    -- Currency
    currency_code       VARCHAR(3) NOT NULL DEFAULT 'VND',
    exchange_rate       DECIMAL(10, 6) DEFAULT 1.0,
    
    -- Discount breakdown
    discount_breakdown  JSONB,
    /*
    {
        "promotion_discounts": [
            {"promotion_id": "...", "amount": 50000, "code": "SUMMER20"}
        ],
        "coupon_discount": 30000,
        "loyalty_points_discount": 10000
    }
    */
    
    -- Shipping address (snapshot)
    shipping_address    JSONB NOT NULL,
    /*
    {
        "recipient_name": "Nguyễn Văn A",
        "phone": "0901234567",
        "address_line1": "123 Đường ABC",
        "address_line2": "Phường XYZ",
        "district": "Quận 1",
        "city": "TP.HCM",
        "postal_code": "700000",
        "country": "VN",
        "coordinates": {"lat": 10.762622, "lng": 106.660172}
    }
    */
    
    -- Billing address (if different)
    billing_address     JSONB,
    
    -- Payment
    payment_method      VARCHAR(50) NOT NULL,
    payment_method_details JSONB,
    /*
    {
        "type": "CREDIT_CARD",
        "last_four": "4242",
        "brand": "visa",
        "exp_month": 12,
        "exp_year": 2025
    }
    */
    
    -- Delivery
    estimated_delivery_from TIMESTAMPTZ,
    estimated_delivery_to   TIMESTAMPTZ,
    actual_delivery_at      TIMESTAMPTZ,
    
    -- Customer notes
    customer_note       TEXT,
    internal_note       TEXT,
    
    -- Email confirmation
    confirmation_email  VARCHAR(255),
    confirmed_at        TIMESTAMPTZ,
    confirmation_expires_at TIMESTAMPTZ,
    
    -- Cancellation
    cancelled_at        TIMESTAMPTZ,
    cancellation_reason VARCHAR(50),
    cancellation_note   TEXT,
    cancelled_by        VARCHAR(20),  -- USER, SYSTEM, SELLER, ADMIN
    
    -- Flags
    is_gift             BOOLEAN NOT NULL DEFAULT false,
    gift_message        TEXT,
    requires_signature  BOOLEAN NOT NULL DEFAULT false,
    
    -- Pre-order
    is_preorder         BOOLEAN NOT NULL DEFAULT false,
    expected_ship_date  DATE,
    
    -- IP & Fraud
    ip_address          INET,
    risk_score          DECIMAL(3, 2),
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_orders_user ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders(status, created_at DESC);
CREATE INDEX idx_orders_number ON orders(order_number);
CREATE INDEX idx_orders_date ON orders(created_at DESC);
CREATE INDEX idx_orders_confirmation ON orders(status, confirmation_expires_at) 
    WHERE status = 'PENDING_CONFIRMATION';
```

#### Table: `sub_orders`
```sql
-- Orders split by seller
CREATE TABLE sub_orders (
    sub_order_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders(order_id),
    
    -- Sub-order number
    sub_order_number    VARCHAR(35) NOT NULL UNIQUE,  -- order_number-001
    
    -- Seller
    seller_id           UUID NOT NULL,
    
    -- Status (can differ from main order)
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (
        status IN (
            'PENDING', 'PROCESSING', 'READY_TO_SHIP', 
            'SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED'
        )
    ),
    
    -- Amounts
    subtotal            DECIMAL(15, 2) NOT NULL,
    discount_amount     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    shipping_amount     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    total               DECIMAL(15, 2) NOT NULL,
    
    -- Shipping
    shipping_method_id  UUID,
    tracking_number     VARCHAR(100),
    shipped_at          TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    
    -- Warehouse
    fulfillment_warehouse_id UUID,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_suborders_order ON sub_orders(order_id);
CREATE INDEX idx_suborders_seller ON sub_orders(seller_id, created_at DESC);
CREATE INDEX idx_suborders_status ON sub_orders(status);
```

#### Table: `order_items`
```sql
CREATE TABLE order_items (
    item_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders(order_id),
    sub_order_id        UUID NOT NULL REFERENCES sub_orders(sub_order_id),
    
    -- Product (snapshot)
    product_id          UUID NOT NULL,
    sku_id              UUID NOT NULL,
    seller_id           UUID NOT NULL,
    
    product_name        VARCHAR(500) NOT NULL,
    variant_info        VARCHAR(200),
    image_url           TEXT,
    sku_code            VARCHAR(100),
    
    -- Quantity
    quantity            INTEGER NOT NULL,
    
    -- Pricing at order time
    unit_price          DECIMAL(15, 2) NOT NULL,
    original_price      DECIMAL(15, 2),  -- Before discount
    subtotal            DECIMAL(15, 2) NOT NULL,  -- unit_price * quantity
    
    -- Item-level discount
    discount_amount     DECIMAL(15, 2) NOT NULL DEFAULT 0,
    discount_breakdown  JSONB,
    
    -- Final
    total               DECIMAL(15, 2) NOT NULL,
    
    -- Item status (for partial operations)
    item_status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (
        item_status IN ('ACTIVE', 'CANCELLED', 'RETURNED', 'REFUNDED')
    ),
    cancelled_quantity  INTEGER DEFAULT 0,
    returned_quantity   INTEGER DEFAULT 0,
    
    -- Fulfillment
    warehouse_id        UUID,
    
    -- Weight/dimensions (for shipping calc)
    weight_kg           DECIMAL(10, 3),
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_suborder ON order_items(sub_order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);
```

#### Table: `order_status_history`
```sql
CREATE TABLE order_status_history (
    history_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders(order_id),
    sub_order_id        UUID,  -- NULL if main order status change
    
    -- Status change
    from_status         VARCHAR(30),
    to_status           VARCHAR(30) NOT NULL,
    
    -- Context
    changed_by          UUID,
    changed_by_type     VARCHAR(20) NOT NULL,  -- USER, SYSTEM, SELLER, ADMIN
    reason              TEXT,
    metadata            JSONB,
    
    -- Timestamp
    changed_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_history_order ON order_status_history(order_id, changed_at DESC);
```

### 3.3 Event Sourcing (Implemented in order-service)

Reference: [order-service/ARCHITECTURE.md](../order-service/ARCHITECTURE.md)

#### Key Events
```java
// From order-service implementation
@DomainEvent(aggregateType = "Order")
public class OrderCreatedEvent extends OrderEvent {
    private String orderNumber;
    private String userId;
    private List<OrderItemDto> items;
    private AddressDto shippingAddress;
    private BigDecimal grandTotal;
}

@DomainEvent(aggregateType = "Order")
public class OrderConfirmationRequestedEvent extends OrderEvent {
    private String email;
    private String otpCode;  // Stored hashed
    private Instant expiresAt;
}

@DomainEvent(aggregateType = "Order")
public class OrderConfirmedEvent extends OrderEvent {
    private Instant confirmedAt;
}

@DomainEvent(aggregateType = "Order")
public class OrderPaymentProcessingEvent extends OrderEvent {
    private String paymentIntentId;
}

@DomainEvent(aggregateType = "Order")
public class OrderPaidEvent extends OrderEvent {
    private String transactionId;
    private BigDecimal amount;
    private String paymentMethod;
}

@DomainEvent(aggregateType = "Order")
public class OrderShippedEvent extends OrderEvent {
    private String subOrderId;
    private String trackingNumber;
    private String carrier;
}

@DomainEvent(aggregateType = "Order")
public class OrderDeliveredEvent extends OrderEvent {
    private Instant deliveredAt;
    private String signedBy;
}

@DomainEvent(aggregateType = "Order")
public class OrderCancelledEvent extends OrderEvent {
    private String reason;
    private String cancelledBy;
    private boolean requiresRefund;
}
```

### 3.4 Order State Machine

```
┌───────────────────────────────────────────────────────────────────────┐
│                        Order State Machine                            │
└───────────────────────────────────────────────────────────────────────┘

                              ┌─────────┐
                              │ PENDING │
                              └────┬────┘
                                   │
                    Send confirmation email
                                   │
                                   ▼
                    ┌──────────────────────────┐
                    │ PENDING_CONFIRMATION     │──── 30min timeout ────┐
                    │ (waiting for OTP)        │                       │
                    └────────────┬─────────────┘                       │
                                 │                                     │
                         OTP Verified                                  │
                                 │                                     │
                                 ▼                                     │
                           ┌───────────┐                               │
                           │ CONFIRMED │                               │
                           └─────┬─────┘                               │
                                 │                                     │
              Start Temporal Saga (Payment + Inventory)                │
                                 │                                     │
                                 ▼                                     │
                         ┌────────────┐                                │
              ┌──────────│ PROCESSING │────────────┐                   │
              │          └────────────┘            │                   │
              │                                    │                   │
        Payment OK                           Payment Failed            │
              │                                    │                   │
              ▼                                    ▼                   ▼
          ┌──────┐                            ┌────────┐        ┌───────────┐
          │ PAID │                            │ FAILED │        │ CANCELLED │
          └──┬───┘                            └────────┘        └───────────┘
             │                                                        ▲
     Ship all items                                                   │
             │                                           User/System Cancel
             ▼                                                        │
        ┌─────────┐                                                   │
        │ SHIPPED │◀─────────────────────────────────────────────────┤
        └────┬────┘                                                   │
             │                                                        │
      Mark delivered                                                  │
             │                                                        │
             ▼                                                        │
       ┌───────────┐                                                  │
       │ DELIVERED │──────────────────────────────────────────────────┤
       └─────┬─────┘                                                  │
             │                                                        │
     ┌───────┴───────┐                                                │
     │               │                                                │
  Complete     Return items                                           │
     │               │                                                │
     ▼               ▼                                                │
┌───────────┐  ┌──────────┐                                           │
│ COMPLETED │  │ RETURNED │                                           │
└───────────┘  └────┬─────┘                                           │
                    │                                                 │
              Process refund                                          │
                    │                                                 │
                    ▼                                                 │
               ┌──────────┐                                           │
               │ REFUNDED │◀──────────────────────────────────────────┘
               └──────────┘
```

### 3.5 Temporal Saga Workflow

```java
@WorkflowInterface
public interface OrderProcessingSaga {
    @WorkflowMethod
    OrderProcessingResult process(ProcessOrderCommand command);
    
    @SignalMethod
    void cancelOrder(String reason);
}

@WorkflowImpl
public class OrderProcessingSagaImpl implements OrderProcessingSaga {
    
    private final PaymentActivities paymentActivities;
    private final InventoryActivities inventoryActivities;
    private final ShippingActivities shippingActivities;
    
    @Override
    public OrderProcessingResult process(ProcessOrderCommand cmd) {
        String orderId = cmd.getOrderId();
        
        try {
            // Step 1: Reserve Inventory
            ReservationResult reservation = inventoryActivities.reserveStock(
                cmd.getOrderId(), 
                cmd.getItems()
            );
            
            // Step 2: Process Payment
            PaymentResult payment = paymentActivities.processPayment(
                cmd.getOrderId(),
                cmd.getPaymentDetails(),
                cmd.getAmount()
            );
            
            // Step 3: Confirm Reservation
            inventoryActivities.confirmReservation(reservation.getReservationId());
            
            // Step 4: Create Shipment
            ShipmentResult shipment = shippingActivities.createShipment(
                cmd.getOrderId(),
                cmd.getShippingAddress(),
                reservation.getWarehouseId()
            );
            
            return OrderProcessingResult.success(payment, shipment);
            
        } catch (PaymentFailedException e) {
            // Compensation: Release inventory
            inventoryActivities.releaseReservation(cmd.getOrderId());
            throw Workflow.wrap(e);
            
        } catch (InsufficientStockException e) {
            // No payment was made, just fail
            throw Workflow.wrap(e);
        }
    }
}
```

---

## 4. Cross-Service Integration

### 4.1 Event Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Shopping Event Flow                             │
└──────────────────────────────────────────────────────────────────────────┘

Cart Service ─────────────────────────────────────────────────────────────┐
    │                                                                     │
    │─── CartConvertedToOrderEvent ─────────────────────────────────────▶ │
    │    {cartId, orderId, items}                              Order Service
    │                                                                     │
    │◀── OrderCreatedEvent ─────────────────────────────────────────────  │
    │    (Clear cart)                                                     │
    └─────────────────────────────────────────────────────────────────────┘

Promotion Service ────────────────────────────────────────────────────────┐
    │                                                                     │
    │◀── OrderPaidEvent ──────────────────────────────────────────────── │
    │    (Update usage count, budget)                          Order Service
    │                                                                     │
    │◀── OrderCancelledEvent ─────────────────────────────────────────── │
    │    (Rollback usage if order cancelled before completion)            │
    │                                                                     │
    └─────────────────────────────────────────────────────────────────────┘

                                    │
                                    │
               ┌────────────────────┴────────────────────┐
               │                                         │
               ▼                                         ▼
    Payment Service                           Inventory Service
    (See Group 4)                             (See Group 4)
```

### 4.2 Saga Orchestration

```
                              Order Service
                                   │
                                   │ ProcessOrderCommand
                                   ▼
                    ┌──────────────────────────────┐
                    │     Temporal Workflow        │
                    │    (OrderProcessingSaga)     │
                    └──────────────────────────────┘
                           │         │         │
            ┌──────────────┤         │         ├──────────────┐
            ▼              ▼         ▼         ▼              ▼
      ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
      │ Inventory│  │ Payment  │  │ Shipping │  │ Notif.   │
      │ Reserve  │  │ Process  │  │ Create   │  │ Send     │
      └──────────┘  └──────────┘  └──────────┘  └──────────┘
            │              │            │             │
            │     Compensation Flow (on failure)      │
            │◀─────────────┤            │             │
            │   Release    │            │             │
            └──────────────┴────────────┴─────────────┘
```

---

## 5. Kafka Topics & Redis Caching

### 5.1 Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `cart-events` | Cart Service | Analytics, Order | CartEvent |
| `order-events` | Order Service | Payment, Inventory, Shipping, Analytics | OrderEvent |
| `order-events-dlt` | Kafka (dead-letter) | Monitoring | Failed OrderEvent |
| `promotion-events` | Promotion Service | Analytics, Notification | PromotionEvent |

### 5.2 Redis Caching

| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `cart:{cart_id}` | 30 days | Full cart cache (guest) |
| `cart:user:{user_id}` | Session | User cart reference |
| `promo:active:{type}` | 5 min | Active promotions by type |
| `promo:code:{code}` | 10 min | Coupon code validation |
| `promo:usage:{promo_id}:{user_id}` | 1 hour | User's usage count |
| `order:confirm:{order_id}` | 30 min | OTP confirmation |
| `order:rate_limit:{user_id}` | 1 hour | Order creation rate limit |

### 5.3 Redis Data Structures

```
# Cart (Hash for efficient field updates)
HSET cart:abc-123 
    "user_id" "user-1"
    "items" '[{"sku_id":"...", "qty":2, ...}]'
    "subtotal" "500000"
    "updated_at" "2025-02-01T12:00:00Z"
EXPIRE cart:abc-123 2592000  # 30 days

# Active promotions (Sorted Set by priority)
ZADD promo:active:PERCENTAGE 10 "promo-1" 5 "promo-2"
EXPIRE promo:active:PERCENTAGE 300

# Coupon validation (String)
SET promo:code:SUMMER20 '{"promo_id":"...", "valid":true, "remaining":50}'
EXPIRE promo:code:SUMMER20 600

# User promotion usage (Counter)
INCR promo:usage:promo-1:user-123
EXPIRE promo:usage:promo-1:user-123 3600

# Order confirmation OTP (String)
SET order:confirm:order-abc '{"otp_hash":"...", "email":"user@example.com", "attempts":0}'
EXPIRE order:confirm:order-abc 1800  # 30 min

# Rate limiting (Counter)
INCR order:rate_limit:user-123
EXPIRE order:rate_limit:user-123 3600
```

---

## Summary

### Tables Created

| Service | Tables | Purpose |
|---------|--------|---------|
| **Cart** | carts, cart_items, saved_items | Shopping cart management |
| **Promotion** | promotions, promotion_tiers, promotion_usage, coupons, promotion_stacking_rules | Discount engine |
| **Order** | orders, sub_orders, order_items, order_status_history | Order lifecycle |

### Key Design Decisions

1. **Guest cart merge** preserves user's existing cart, sums quantities on conflict
2. **Drools rules engine** for complex promotion logic (tiered, buy-x-get-y)
3. **Sub-orders per seller** enable independent fulfillment tracking
4. **10-state order machine** with email confirmation as fraud prevention
5. **Temporal Saga** orchestrates payment + inventory + shipping with compensation
6. **Price snapshots** at add-to-cart and order creation for dispute resolution
