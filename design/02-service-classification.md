# Service Classification - 17 Microservices

**Last Updated**: February 13, 2026  
**Pattern Distribution**: 9 CRUD | 4 CQRS | 4 ES | 2 Full Stack

## Classification Overview

| Tier | Pattern | Services Count | % of Total | Avg Complexity |
|------|---------|----------------|------------|----------------|
| 🟢 **Tier 1** | Simple CRUD | 9 | 53% | Low (2 weeks) |
| 🟡 **Tier 2** | CQRS Only | 4 | 23% | Medium (3 weeks) |
| 🟠 **Tier 3** | Event Sourcing | 4 | 18% | High (4 weeks) |
| 🔴 **Tier 4** | Full Stack (ES+CQRS+Saga) | 2 | 6% | Very High (6 weeks) |

---

## 🟢 Tier 1: Simple CRUD (9 Services)

### Characteristics
- Traditional RDBMS patterns (PostgreSQL + Redis)
- Simple CRUD operations
- Event publishing for integration (NOT event sourcing)
- No complex state machines
- No distributed transactions
- **Development Time**: 2 weeks per service

---

### 1. Auth Service ✅ (Implemented - Go)

**Repository**: [`go/services/auth-service`](../go/services/auth-service)  
**Database Schema**: [`database/01-identity-services.md`](../database/01-identity-services.md)

**Purpose**: Authentication, token management, session management, OAuth

**Tech Stack**:
- **Language**: Go (high-performance, low-latency)
- **Database**: PostgreSQL (4 tables) + Redis (8 key patterns)
- **Status**: ✅ Fully implemented

**Why Simple CRUD**:
- ✅ JWT tokens stateless → no complex state
- ✅ Login attempts logged in `auth_login_attempts` table (audit sufficient)
- ✅ Session management với Redis TTL (no need for event replay)
- ✅ Token blacklist in Redis với expiry
- ❌ No complex workflows (login/logout are atomic operations)

**Database Tables**:
1. `auth_sessions` - Active session tracking
2. `auth_login_attempts` - Audit trail for brute-force detection
3. `auth_password_reset_tokens` - One-time reset tokens
4. Redis keys: `otp:email:*`, `refresh:*`, `token:blacklist:*`

**Event Publishing** (for integration only):
- `UserLoggedInEvent` → Analytics Service (fraud detection)
- `TokenRefreshedEvent` → Audit logs
- `SuspiciousActivityEvent` → Security monitoring

**NOT Event Sourcing Because**:
- Users không dispute "Ai login tài khoản tôi?" (session logs sufficient)
- Token rotation tracked in Redis, không cần rebuild history
- Login attempts table provides audit trail without event store overhead

---

### 2. User Service (To Implement - Go)

**Database Schema**: [`database/01-identity-services.md`](../database/01-identity-services.md)

**Purpose**: User profiles, addresses, preferences, restriction management

**Tech Stack**:
- **Language**: Go
- **Database**: PostgreSQL (4 tables) + Redis (cache)
- **Status**: ⚠️ Not yet implemented

**Why Simple CRUD**:
- ✅ Profile updates infrequent (< 1/month per user)
- ✅ Restriction state machine simple (5 states: NONE, WARNING, LIMITED, SUSPENDED, BANNED)
- ✅ `restriction_status_history` table provides audit
- ❌ No need to replay "What was user's address 6 months ago?"

**Database Tables**:
1. `users` - Core user profiles
2. `user_addresses` - Shipping addresses
3. `user_preferences` - UI preferences, AI settings
4. `restriction_status_history` - Changes to restriction status

**Cache Strategy**:
```redis
user:profile:{user_id}         # TTL: 1h
user:addresses:{user_id}       # TTL: 24h
user:preferences:{user_id}     # TTL: 7 days
```

**Event Publishing**:
- `UserDeactivatedEvent` → Cascade to Auth (revoke sessions), Seller (deactivate shops)
- `UserAddressChangedEvent` → Update shipping estimates

---

### 3. Cart Service (Stub exists - Go)

**Repository**: [`go/services/cart-service`](../go/services/cart-service)  
**Database Schema**: [`database/03-shopping-services.md`](../database/03-shopping-services.md)

**Purpose**: Shopping cart management, guest merging, item selection

**Tech Stack**:
- **Language**: Go
- **Database**: Redis (primary) + PostgreSQL (backup)
- **Status**: ⚠️ Stub exists, needs completion

**Why Simple CRUD**:
- ✅ Cart là **transient state** (< 7 days TTL)
- ✅ Không cần audit trail cho "User added milk to cart"
- ✅ Abandoned carts reconstructed from final snapshot
- ❌ Cart operations không cần time-travel capability

**Architecture**:
```
Write: Redis (active carts) → PostgreSQL (backup after 5min inactivity)
Read: Redis first → Fallback PostgreSQL if expired
```

**Event Publishing**:
- `CartAbandonedEvent` → Notification Service (recovery email after 24h)
- `CartConvertedEvent` → Analytics (conversion funnel metrics)

---

### 4. Notification Service (Stub exists - Go)

**Repository**: [`go/services/notification-service`](../go/services/notification-service)  
**Database Schema**: [`database/05-engagement-services.md`](../database/05-engagement-services.md)

**Purpose**: Multi-channel notifications (email, SMS, push, in-app)

**Tech Stack**:
- **Language**: Go
- **Database**: PostgreSQL (queue) + Redis (rate limiting)
- **Status**: ⚠️ Stub exists

**Why Simple CRUD**:
- ✅ Notifications fire-and-forget
- ✅ Delivery status tracked, but không cần replay
- ✅ Template rendering stateless
- ❌ Users không query "Emails sent to me last month"

**Architecture**:
```
Kafka consumers → PostgreSQL queue → Background workers → Providers
                     ↓ (polling)
              delivery_status updates
```

**Pattern**: Consumer all events from platform → Match to templates → Queue → Send

---

### 5. Chat Service (Stub exists - Go)

**Repository**: [`go/services/chat-service`](../go/services/chat-service)  
**Database Schema**: [`database/05-engagement-services.md`](../database/05-engagement-services.md)

**Purpose**: Real-time messaging between users/sellers

**Tech Stack**:
- **Language**: Go
- **Database**: ScyllaDB (optimized for time-series)
- **Status**: ⚠️ Stub exists

**Why Simple CRUD**:
- ✅ **ScyllaDB IS the event log** (messages immutable, append-only)
- ✅ Partitioned by `(room_id, timestamp)` for fast queries
- ✅ No need for separate event store
- ❌ Messages already ordered by clustering key

**Schema**:
```cql
CREATE TABLE messages (
    room_id UUID,
    message_id TIMEUUID,
    sender_id UUID,
    content TEXT,
    PRIMARY KEY (room_id, message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

**NOT Event Sourcing Because**: ScyllaDB's append-only nature already provides immutability

---

### 6-9. Other Tier 1 Services

**6. Analytics Service** (Python, TimescaleDB)
- **Why CRUD**: Consumes events, doesn't produce them. TimescaleDB hypertables optimized for time-series ingestion.

**7. Search Service** (Go, Elasticsearch)
- **Why CRUD**: Elasticsearch IS the read model. Subscribe to `product-events` → Update index.

---

## 🟡 Tier 2: CQRS Only (4 Services)

### Characteristics
- Write model normalized (PostgreSQL)
- Read model denormalized (materialized views / Elasticsearch)
- Async synchronization via Kafka events
- No need to replay full history
- **Development Time**: 3 weeks per service

---

### 10. Product Service (Java - CQRS) ⬅️ **DOWNGRADED từ ES**

**Database Schema**: [`database/02-catalog-services.md`](../database/02-catalog-services.md)

**Purpose**: Product catalog, SKU management, pricing

**Tech Stack**:
- **Language**: Java/Spring Boot
- **Database**: PostgreSQL + Elasticsearch (Search Service)
- **Status**: ⚠️ Not yet implemented

**Why CQRS Sufficient** (Not Event Sourcing):
- ✅ Product data **ít thay đổi** (price updates 1-2 times/month)
- ✅ Price history với **audit table** đủ (không cần event replay)
- ✅ **Cart/Order snapshot giá** tại thời điểm checkout → No disputes về product price
- ❌ Users không dispute "Giá sản phẩm 3 tháng trước là bao nhiêu?" (Cart đã lưu)

**Architecture**:
```
┌─────────────────────────────────────────────────┐
│ WRITE MODEL (Normalized)                        │
├─────────────────────────────────────────────────┤
│ products (product_id, name, description)        │
│ product_skus (sku_id, price, stock)             │
│ product_attributes (key, value)                 │
│ product_price_history (audit trail)             │ ← Simple audit table
└─────────────────────────────────────────────────┘
                ↓ Publish events
          Kafka: "product-events"
                ↓
┌─────────────────────────────────────────────────┐
│ READ MODEL (Denormalized)                       │
├─────────────────────────────────────────────────┤
│ products_catalog_view (materialized view)       │
│ ├─ product_id, name, price, rating              │
│ ├─ seller_shop_name (join eliminated)           │
│ └─ category_path (precomputed breadcrumb)       │
└─────────────────────────────────────────────────┘
                ↓ Indexed in
          Elasticsearch (Search Service)
```

**Price History** (audit table, not event store):
```sql
CREATE TABLE product_price_history (
    history_id SERIAL PRIMARY KEY,
    product_id UUID,
    old_price DECIMAL,
    new_price DECIMAL,
    changed_by UUID,
    changed_at TIMESTAMP,
    reason VARCHAR(255)
);

-- Trigger auto-log on price change
CREATE TRIGGER log_price_change
AFTER UPDATE OF price ON products
FOR EACH ROW EXECUTE FUNCTION log_price_change_fn();
```

**Cache Strategy**:
```redis
product:details:{product_id}     # TTL: 1h
product:reviews:{product_id}     # TTL: 5min (frequently updated)
```

**Event Publishing** (for integration, NOT event sourcing):
- `ProductCreatedEvent` → Search Service (index), Recommendation Service
- `ProductPriceChangedEvent` → Cart Service (notify users with item in cart)
- `ProductDeactivatedEvent` → Search Service (remove from index)

**Decision Rationale**: Downgraded từ Tier 3 (ES) because:
1. Product updates rare → Event replay ít giá trị
2. Cart/Order snapshot prices → Không cần product price history
3. Audit table đủ cho compliance → Event store overkill (60% complexity reduction)

---

### 11. Seller Service (Java - CQRS)

**Database Schema**: [`database/01-identity-services.md`](../database/01-identity-services.md)

**Purpose**: Shop management, seller verification (KYC), policies

**Why CQRS**:
- ✅ **Write model**: Verification workflow (PENDING → APPROVED → ACTIVE)
- ✅ **Read model**: Shop info for product listings (precomputed stats)
- ✅ Read >> Write (shop displayed on every product page)

**Read Model**:
```sql
CREATE MATERIALIZED VIEW seller_shop_summary AS
SELECT 
    s.seller_id,
    s.shop_name,
    s.verification_status,
    COUNT(p.product_id) as product_count,
    AVG(r.rating) as avg_rating,
    s.business_policies  -- JSONB with AI embeddings
FROM sellers s
LEFT JOIN products p ON s.seller_id = p.seller_id
LEFT JOIN reviews r ON p.product_id = r.product_id
GROUP BY s.seller_id;
```

**Event-Driven Sync**:
- Product Service: `ProductCreatedEvent` → Increment seller's `product_count`
- Review Service: `ReviewApprovedEvent` → Recalculate seller's `avg_rating`

---

### 12. Review Service (Java - CQRS)

**Database Schema**: [`database/05-engagement-services.md`](../database/05-engagement-services.md)

**Purpose**: Product reviews, ratings, moderation

**Why CQRS**:
- ✅ **Write model**: Individual reviews với moderation workflow
- ✅ **Read model**: Aggregated stats (display on product page)
- ✅ Read >> Write (reviews queried on every product view)

**Read Model**:
```sql
CREATE MATERIALIZED VIEW product_review_stats AS
SELECT 
    product_id,
    COUNT(*) as total_reviews,
    AVG(rating) as avg_rating,
    COUNT(*) FILTER (WHERE rating = 5) as five_star,
    COUNT(*) FILTER (WHERE rating = 4) as four_star,
    COUNT(*) FILTER (WHERE rating >= 4) * 100.0 / COUNT(*) as positive_ratio
FROM product_reviews
WHERE status = 'APPROVED'
GROUP BY product_id;
```

**NOT Event Sourcing Because**:
- Review edit history table sufficient (`review_edit_history`)
- Users rarely query "Lịch sử review của tôi"
- Moderation simple (PENDING → APPROVED/REJECTED)

---

### 13. AI Agent Service (Python - CQRS)

**Database Schema**: [`database/06-intelligence-services.md`](../database/06-intelligence-services.md)

**Purpose**: Chatbot, intent detection, RAG (knowledge retrieval)

**Why CQRS**:
- ✅ **Write model**: Consume events → Generate embeddings → Store chunks
- ✅ **Read model**: Vector-indexed knowledge base (pgvector)
- ✅ Read-optimized for RAG retrieval

**Architecture**:
```
Write Side: Kafka events → LangChain splitter → OpenAI embeddings → PostgreSQL
Read Side: User query → Vector search → Top 5 chunks → LLM context
```

**NOT Event Sourcing Because**:
- Conversations logged but không cần replay
- Knowledge base rebuildable from source events
- LLM responses non-deterministic

---

## 🟠 Tier 3: Event Sourcing (4 Services)

### Characteristics
- Event store for ALL state changes
- Rebuild state from events (time-travel capability)
- Compliance/audit requirements
- CQRS for read optimization (implied)
- NO multi-service transactions (sagas)
- **Development Time**: 4 weeks per service

---

### 14. Promotion Service (Java - ES)

**Database Schema**: [`database/03-shopping-services.md`](../database/03-shopping-services.md)

**Purpose**: Dynamic discounts, coupons, budget tracking

**Why Event Sourcing**:
- ✅ **Budget tracking**: Prevent double-spending with event sequence
- ✅ **Fraud detection**: Detect coordinated coupon abuse (same IP, multiple codes)
- ✅ **Compliance**: Audit promotional spending for partner agreements
- ✅ **Dispute resolution**: "Mã giảm giá không áp dụng" → Replay events để debug

**Critical Problem - Concurrency**:
```
Promotion Budget: 1,000,000 VND remaining
User A: Apply 100k coupon → Check budget OK → Deduct
User B: Apply 100k coupon → Check budget OK (SAME TIME) → Deduct
❌ Race condition: Budget can go negative
```

**Solution**: Event Sourcing + Optimistic Locking
```java
@Entity
public class Promotion {
    @Version
    private Integer version;  // ← Optimistic lock
    
    private Long usedBudget;
    
    public void applyPromotion(UserId userId, Long amount) {
        if (usedBudget + amount > totalBudget) {
            throw new InsufficientBudgetException();
        }
        
        PromotionUsedEvent event = PromotionUsedEvent.builder()
            .promotionId(this.id)
            .userId(userId)
            .amount(amount)
            .version(this.version + 1)
            .build();
        
        this.uncommittedEvents.add(event);
    }
}
```

**Event Types**:
- `PromotionCreatedEvent`
- `PromotionUsedEvent` (user_id, order_id, discount_amount, remaining_budget)
- `PromotionBudgetExhaustedEvent`

**Fraud Detection Pattern**:
```sql
-- Detect abuse from same IP
SELECT 
    event_data->>'ip_address' as ip,
    COUNT(DISTINCT event_data->>'user_id') as unique_users
FROM event_store
WHERE event_type = 'PromotionUsedEvent'
  AND occurred_on > NOW() - INTERVAL '1 hour'
GROUP BY event_data->>'ip_address'
HAVING COUNT(DISTINCT event_data->>'user_id') > 5;  -- Suspicious!
```

---

### 15. Inventory Service (Java - ES)

**Database Schema**: [`database/04-fulfillment-services.md`](../database/04-fulfillment-services.md)

**Purpose**: Stock management, reservations, multi-warehouse

**Why Event Sourcing**:
- ✅ **Stock audit trail**: Every movement tracked (receive, ship, adjust, damage, return)
- ✅ **Fraud prevention**: Detect suspicious patterns (mass reservations → cancellations)
- ✅ **FIFO/LIFO accounting**: Calculate cost basis from event history
- ✅ **Warehouse reconciliation**: Replay events to verify physical count

**Event Types**:
- `StockReceivedEvent` (sku_id, warehouse_id, quantity, lot_number)
- `StockReservedEvent` (reservation_id, sku_id, quantity, order_id, expires_at)
- `ReservationReleasedEvent` (reservation_id, reason: EXPIRED/CANCELLED)
- `StockAdjustedEvent` (quantity_delta, reason, adjusted_by)
- `StockDamagedEvent` (quantity, damage_reason)

**Reservation Expiry** (Temporal):
```java
public void reserveStock(ReservationRequest req) {
    activities.reserveStock(req);
    
    // Sleep 15 minutes
    workflow.sleep(Duration.ofMinutes(15));
    
    // Check if order paid
    boolean paid = activities.isOrderPaid(req.getOrderId());
    if (!paid) {
        activities.releaseReservation(req.getReservationId(), "EXPIRED");
    }
}
```

**CQRS Read Model**:
```sql
CREATE MATERIALIZED VIEW inventory_stock_summary AS
SELECT 
    sku_id,
    warehouse_id,
    physical_quantity,
    (SELECT SUM(quantity) FROM stock_reservations 
     WHERE sku_id = s.sku_id AND expires_at > NOW()) as reserved,
    physical_quantity - reserved as available
FROM inventory_stock s;
```

---

### 16. Shipping Service (Go/Java - ES) ⬅️ **UPGRADED từ CRUD**

**Database Schema**: [`database/04-fulfillment-services.md`](../database/04-fulfillment-services.md)

**Purpose**: Carrier integration (GHN, Viettel Post), tracking

**Why Event Sourcing** (Critical!):
- ✅ **Long-running process** (5-7 ngày)
- ✅ **Async webhooks** từ carriers (có thể delayed, duplicate, out-of-order)
- ✅ **Idempotency** critical (same webhook arrives 2-3 times)
- ✅ **State machine validation** (8 states: CREATED → PICKED_UP → IN_TRANSIT → ... → DELIVERED)

**Critical Problems với Simple CRUD**:
```java
// ❌ BAD: Simple status field
@Entity
public class Shipment {
    private String status;  // Không handle:
    // 1. Duplicate webhooks (status flip-flops)
    // 2. Out-of-order events (DELIVERED arrives before IN_TRANSIT)
    // 3. Timeout handling
    // 4. Race conditions (multiple webhooks simultaneously)
}
```

**Solution**: Event Sourcing + Temporal
```java
// Event Store with idempotency
CREATE TABLE shipping_event_store (
    event_id VARCHAR(255) PRIMARY KEY,
    aggregate_id VARCHAR(255),  -- shipment_id
    carrier_event_id VARCHAR(255),  -- ← Idempotency key from carrier
    event_data JSONB,
    occurred_on TIMESTAMP
);

CREATE UNIQUE INDEX idx_carrier_event_idempotency 
ON shipping_event_store(carrier, carrier_event_id);
```

**Webhook Handler** (Idempotent):
```java
@PostMapping("/webhooks/ghn/status")
public ResponseEntity<Void> handleGHNWebhook(@RequestBody GHNWebhookPayload payload) {
    String carrierEventId = payload.getEventId();
    
    // Check if already processed
    if (eventStore.existsByCarrierEventId("GHN", carrierEventId)) {
        return ResponseEntity.ok().build();  // Acknowledge duplicate
    }
    
    // Convert to domain event
    ShipmentEvent event = convertToDomainEvent(payload);
    
    // Validate state transition (prevent out-of-order)
    if (!isValidTransition(shipmentId, event)) {
        return ResponseEntity.ok().build();  // Ignore invalid transition
    }
    
    // Save event
    eventStore.save(event);
    
    // Signal Temporal workflow
    temporalClient.signalWorkflow("shipment-" + shipmentId, "status-update", event);
    
    return ResponseEntity.ok().build();
}
```

**Temporal Workflow**:
```java
@Workflow
public ShipmentResult processShipment(ShipmentRequest req) {
    // Create shipment with carrier
    activities.createCarrierShipment(req);
    
    // Wait for pickup (max 24h)
    boolean pickedUp = Workflow.await(
        Duration.ofHours(24),
        () -> state.getStatus() == PICKED_UP
    );
    
    if (!pickedUp) {
        activities.cancelShipment(req.getShipmentId());
        return ShipmentResult.failed("PICKUP_TIMEOUT");
    }
    
    // Wait for delivery (max 7 days)
    boolean delivered = Workflow.await(
        Duration.ofDays(7),
        () -> state.getStatus() == DELIVERED
    );
    
    return ShipmentResult.success(state);
}
```

**Event Types**:
- `ShipmentCreatedEvent`
- `ShipmentPickedUpEvent` (carrier_tracking_number)
- `ShipmentInTransitEvent` (current_hub, next_hub)
- `DeliveryAttemptedEvent` (attempt_number, failure_reason)
- `ShipmentDeliveredEvent` (signature, photo_proof)
- `ShipmentReturnedToSellerEvent`

**Decision Rationale**: Upgraded từ Tier 1 (CRUD) because:
1. Long-running workflows (days) → Need Temporal
2. Async webhooks with duplicates → Need idempotency
3. Out-of-order events → Need state machine validation
4. Customer queries "Đơn hàng đang ở đâu?" → Need complete history

---

### 17. Refund Service (Java - ES)

**Database Schema**: [`database/04-fulfillment-services.md`](../database/04-fulfillment-services.md)

**Purpose**: Refund processing, dispute management, partial refunds

**Why Event Sourcing**:
- ✅ **Chargeback disputes**: Need complete audit trail for credit card disputes
- ✅ **Partial refunds**: Track multiple refund requests for same order
- ✅ **Approval chain**: Multi-level approval (CS → Manager → Finance)
- ✅ **Regulatory compliance**: Tax authorities require immutable refund records

**Event Types**:
- `RefundRequestedEvent` (refund_id, order_id, amount, reason)
- `RefundApprovedEvent` (approved_by, approval_level)
- `RefundRejectedEvent` (rejected_by, rejection_reason)
- `RefundProcessedEvent` (payment_transaction_id)
- `ItemRestockedEvent` (sku_id, quantity, warehouse_id)

**Saga Compensation** (Temporal):
```java
public void refundSaga(RefundRequest req) {
    try {
        // Step 1: Refund payment
        activities.refundPayment(req);
        
        // Step 2: Restock inventory (if return)
        if (req.isReturnRequired()) {
            activities.restockInventory(req);
        }
        
        // Step 3: Update order status
        activities.updateOrderStatus(req.getOrderId(), "REFUNDED");
        
    } catch (Exception e) {
        // Compensation
        activities.compensateRefund(req);
    }
}
```

---

## 🔴 Tier 4: Full Stack (2 Services Only!)

### Characteristics
- Event Sourcing for immutability
- CQRS for multiple read models
- **Temporal sagas** for distributed transactions
- Compensation logic (rollback on failure)
- Strong consistency guarantees
- **Development Time**: 6 weeks per service

---

### 18. Order Service ✅ (Java - Reference Implementation)

**Repository**: [`java/common`](../java/common), Order service in Spring Boot  
**Database Schema**: [`database/03-shopping-services.md`](../database/03-shopping-services.md)

**Purpose**: Order orchestration, 10-state machine, saga coordination

**Why Full Stack**:
- ✅ **Event Sourcing**: Legal requirement for order audit trail (disputes, refunds, compliance)
- ✅ **CQRS**: Multiple read models
  - Customer view (order history)
  - Seller view (sub-orders by seller)
  - Analytics view (revenue reports)
- ✅ **Saga**: Multi-step distributed transaction
  1. Reserve Inventory → Compensate: Release
  2. Process Payment → Compensate: Refund
  3. Create Shipment → Compensate: Cancel

**10-State Machine**:
```
PENDING → PENDING_CONFIRMATION (email OTP)
   ↓              ↓
CANCELLED        PROCESSING (saga start)
                    ↓
                  PAID
                    ↓
                 SHIPPED
                    ↓
                 DELIVERED
                    ↓ (optional)
                 RETURNED → REFUNDED
```

**Temporal Saga**:
```java
public OrderResult orderSaga(OrderRequest req) {
    try {
        // Activity 1: Reserve inventory (15 min expiry)
        var reservation = activities.reserveInventory(req.getItems());
        
        // Activity 2: Process payment
        var payment = activities.processPayment(req);
        
        // Activity 3: Create shipment
        var shipment = activities.createShipment(req);
        
        return OrderResult.success();
        
    } catch (InventoryException e) {
        // No compensation (reservation auto-expires)
        throw e;
        
    } catch (PaymentException e) {
        // Compensate: Release inventory
        activities.releaseReservation(reservation.getId());
        throw e;
        
    } catch (ShippingException e) {
        // Compensate: Refund payment + Release inventory
        activities.refundPayment(payment.getId());
        activities.releaseReservation(reservation.getId());
        throw e;
    }
}
```

**Event Types** (15+ events):
- `OrderCreatedEvent`, `OrderPaidEvent`, `OrderShippedEvent`, `OrderDeliveredEvent`
- `OrderCancelledEvent`, `OrderReturnedEvent`, `OrderRefundedEvent`

**CQRS Read Models**:
```sql
-- Customer view
CREATE VIEW customer_orders_view AS
SELECT order_id, user_id, total_amount, status, estimated_delivery
FROM orders WHERE user_id = ?;

-- Seller view (sub-orders)
CREATE VIEW seller_orders_view AS
SELECT sub_order_id, seller_id, items, commission
FROM order_sub_orders WHERE seller_id = ?;
```

**Status**: ✅ Reference implementation exists in Java

---

### 19. Payment Service (Java - Full Stack)

**Database Schema**: [`database/04-fulfillment-services.md`](../database/04-fulfillment-services.md)

**Purpose**: Multi-gateway (VNPay, MoMo, Stripe), wallet, escrow

**Why Full Stack**:
- ✅ **Event Sourcing**: **MANDATORY** for financial regulations (immutable ledger)
- ✅ **CQRS**: Multiple read models
  - User wallet balance
  - Seller settlements (batch reports)
  - Platform commission tracking
- ✅ **Saga**: Part of Order Service saga (payment step with refund compensation)

**Critical: Double-Entry Bookkeeping**
```java
public void processPayment(Payment payment) {
    // Every transaction = TWO events (debit + credit)
    
    WalletDebitedEvent debit = WalletDebitedEvent.builder()
        .walletId(payment.getCustomerWalletId())
        .amount(payment.getAmount())
        .transactionId(payment.getPaymentId())
        .build();
    
    WalletCreditedEvent credit = WalletCreditedEvent.builder()
        .walletId(PLATFORM_ESCROW_WALLET_ID)
        .amount(payment.getAmount())
        .transactionId(payment.getPaymentId())
        .build();
    
    // Save atomically
    eventStore.saveBatch(Arrays.asList(debit, credit));
}
```

**Event Types**:
- `PaymentInitiatedEvent`, `PaymentAuthorizedEvent`, `PaymentCapturedEvent`
- `PaymentFailedEvent`, `PaymentRefundedEvent`
- `WalletCreditedEvent`, `WalletDebitedEvent`
- `SellerPayoutInitiatedEvent`, `SellerPayoutCompletedEvent`

**CQRS Read Model**:
```sql
-- Wallet balance (from events)
CREATE MATERIALIZED VIEW user_wallet_balance AS
SELECT 
    wallet_id,
    SUM(CASE WHEN event_type = 'WalletCredited' 
             THEN amount 
             ELSE -amount END) as balance
FROM event_store
WHERE aggregate_type = 'Wallet'
GROUP BY wallet_id;
```

**Saga Integration**:
```java
// Called by Order Service saga
@Activity
public PaymentResult processPayment(PaymentRequest req) {
    // Authorize payment (hold funds)
    var authorization = paymentGateway.authorize(req);
    
    // Generate event
    PaymentAuthorizedEvent event = ...;
    eventStore.save(event);
    
    return PaymentResult.success(authorization);
}

// Compensation
@Activity
public void refundPayment(String paymentId) {
    paymentGateway.refund(paymentId);
    
    PaymentRefundedEvent event = ...;
    eventStore.save(event);
}
```

---

## Summary Statistics

### Development Effort Breakdown

| Tier | Services | Avg Days | Total Days | % of Total Effort |
|------|----------|----------|------------|-------------------|
| Tier 1 | 9 | 10 | 90 | 38% |
| Tier 2 | 4 | 15 | 60 | 25% |
| Tier 3 | 4 | 20 | 80 | 34% |
| Tier 4 | 2 | 30 | 60 | 25% |
| **Total** | **19** | - | **290 days** | **100%** |

*Note: 19 services vì Order Service đã có reference implementation*

### Pattern Distribution

```
Event Sourcing Usage:
  - Full ES (Tier 3 + 4): 6 services (35%)
  - Events for integration only: 13 services (65%)

CQRS Usage:
  - Explicit CQRS (Tier 2 + 3 + 4): 10 services (59%)
  - Simple CRUD: 9 services (41%)

Saga Usage:
  - Temporal Sagas (Tier 4): 2 services (12%)
  - No sagas needed: 17 services (88%)
```

**Key Insight**: Chỉ 12% services cần Saga orchestration - phần lớn là simple operations hoặc fire-and-forget events.

---

## References

- [Architecture Overview](00-architecture-overview.md)
- [Pattern Decision Framework](01-pattern-decision-framework.md)
- [Decision Log](07-decision-log.md)
- Database Schemas: [`/database/`](../database/)
