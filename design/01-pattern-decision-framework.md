# Pattern Decision Framework

**Purpose**: Hướng dẫn quyết định khi nào nên áp dụng Event Sourcing, CQRS, Saga Pattern cho một service

## Decision Matrix

### Event Sourcing - Khi NÀO cần?

| Criteria | ✅ Use Event Sourcing | ❌ Simple CRUD | Examples |
|----------|----------------------|---------------|----------|
| **Audit Trail** | Legal/compliance requirement, mọi thay đổi phải tracked | Simple logging đủ | ✅ Payment, Order<br>❌ Cart, Notification |
| **Time Travel** | Cần rebuild state tại bất kỳ thời điểm nào | Không cần historical reconstruction | ✅ Inventory (stock movement)<br>❌ User profile |
| **Money Movement** | Financial transactions | Non-financial data | ✅ Payment, Refund<br>❌ Product catalog |
| **Fraud Detection** | Cần analyze event patterns | Simple anomaly detection | ✅ Promotion (coupon abuse)<br>❌ Login attempts |
| **Write Frequency** | Moderate writes (< 1000/sec per aggregate) | Very high writes (> 10K/sec) | ✅ Order creation<br>❌ Page view tracking |
| **State Complexity** | Complex state machines (> 5 states) | Simple CRUD | ✅ Shipping (8 states)<br>❌ User status |
| **Dispute Resolution** | Customers có thể dispute transactions | Không có disputes | ✅ Order, Refund<br>❌ Search queries |
| **Regulatory** | GDPR, SOC2, PCI-DSS compliance | No special requirements | ✅ Payment<br>❌ Product reviews |

**Decision Tree**:
```
Is this financial data? (Payment, Wallet, Transaction)
├─ YES → Use Event Sourcing (MANDATORY)
└─ NO → Does it need complete audit trail for compliance?
    ├─ YES → Is write volume < 1000 events/sec?
    │   ├─ YES → Use Event Sourcing
    │   └─ NO → Use append-only log (TimescaleDB) + sampling
    └─ NO → Is state reconstruction critical?
        ├─ YES → Use Event Sourcing
        └─ NO → Simple CRUD sufficient
```

### CQRS - Khi NÀO cần?

| Criteria | ✅ Use CQRS | ❌ Simple CRUD | Examples |
|----------|------------|---------------|----------|
| **Read != Write Model** | Query pattern hoàn toàn khác write | Read/write aligned | ✅ Product catalog (browse vs update)<br>❌ User profile |
| **Read Volume** | Read >> Write (ratio > 10:1) | Balanced read/write | ✅ Product (1M reads, 1K writes/day)<br>❌ Cart (balanced) |
| **Multiple Read Models** | Cần nhiều projections khác nhau | Single view sufficient | ✅ Order (customer view, seller view, analytics)<br>❌ Session data |
| **Complex Queries** | Heavy JOINs, aggregations | Simple lookups | ✅ Review stats (avg rating, distribution)<br>❌ Get user by ID |
| **Scaling Requirements** | Read/write cần scale độc lập | Same scaling profile | ✅ Search (separate Elasticsearch)<br>❌ Auth sessions |
| **Performance** | Sub-100ms query latency required | Standard latency OK | ✅ Product listing<br>❌ Admin dashboard |

**Decision Tree**:
```
Are read queries significantly different from write model?
├─ YES → Is read volume >> write volume?
│   ├─ YES → Use CQRS
│   └─ NO → Can caching solve the problem?
│       ├─ YES → Simple CRUD + Redis cache
│       └─ NO → Use CQRS
└─ NO → Do you need multiple read projections?
    ├─ YES → Use CQRS
    └─ NO → Simple CRUD sufficient
```

### Saga Pattern - Khi NÀO cần?

| Criteria | ✅ Use Saga | ❌ Local Transaction | Examples |
|----------|------------|---------------------|----------|
| **Multi-Service Transaction** | Span 2+ services | Single service | ✅ Order (Inventory + Payment + Shipping)<br>❌ Update user profile |
| **Compensation Logic** | Need rollback distributed changes | ACID transaction sufficient | ✅ Payment failed → release inventory<br>❌ Update cart items |
| **Long-Running** | Process > 5 minutes | Completes in < 1 minute | ✅ Shipping (5-7 ngày)<br>❌ User registration |
| **Async Steps** | Depend on external systems/timeouts | Synchronous operations | ✅ Carrier webhook → shipment status<br>❌ Password hash |
| **Business Process** | Clear workflow with stages | Simple operation | ✅ Refund (approval → payment → restock)<br>❌ Send notification |

**Decision Tree**:
```
Does operation span multiple services?
├─ YES → Can all steps complete synchronously (< 1 min)?
│   ├─ YES → Direct HTTP calls with retry sufficient
│   └─ NO → Is compensation logic needed on failure?
│       ├─ YES → Use Saga (Temporal Workflow)
│       └─ NO → Fire-and-forget events
└─ NO → Single service transaction
    └─ Use local ACID transaction
```

## Pattern Combinations

### Combination Matrix

| Use Case | Event Sourcing | CQRS | Saga | Example Service |
|----------|----------------|------|------|-----------------|
| **Simple CRUD** | ❌ | ❌ | ❌ | Auth, User, Cart |
| **Read Optimization** | ❌ | ✅ | ❌ | Product, Seller, Review |
| **Audit Trail** | ✅ | ✅ (implied) | ❌ | Promotion, Inventory |
| **Complex Workflow** | ✅ | ✅ | ✅ | Order, Payment |

### Valid Combinations

#### 1️⃣ Simple CRUD Only
```
Use When: Standard CRUD + Redis cache sufficient
Pattern:
  - PostgreSQL for persistence
  - Redis for caching
  - Kafka for event publishing (integration only)
  
Examples: Auth, User, Cart, Notification, Chat
```

#### 2️⃣ CQRS Only (No Event Sourcing)
```
Use When: Need read optimization but no audit trail
Pattern:
  - Write Model: Normalized PostgreSQL tables
  - Read Model: Materialized views / Elasticsearch
  - Event-driven sync between models
  
Examples: Product, Seller, Review, AI Agent
```

#### 3️⃣ Event Sourcing + CQRS (No Saga)
```
Use When: Need audit trail + read optimization, local transactions only
Pattern:
  - Event Store: PostgreSQL event_store table
  - Write: Generate events, save to event store
  - Read: Project events to optimized read models
  
Examples: Promotion, Inventory, Shipping, Refund
```

#### 4️⃣ Full Stack (ES + CQRS + Saga)
```
Use When: Complex distributed workflows with compensation
Pattern:
  - Event Sourcing: Complete audit trail
  - CQRS: Multiple read models
  - Saga: Temporal workflows for orchestration
  
Examples: Order, Payment (only 2 services!)
```

### Invalid Combinations

#### ❌ Event Sourcing without CQRS
```
Problem: Event store not optimized for queries
Solution: Always add read models when using ES
```

#### ❌ Saga without Event Sourcing (for critical workflows)
```
Problem: No audit trail for saga steps
Solution: Events should be persisted for saga activities
```

#### ❌ CQRS for write-heavy workloads
```
Problem: Sync overhead > performance gains
Solution: Use simple CRUD with optimistic locking
```

## Anti-Patterns to Avoid

### 🚫 Anti-Pattern 1: Event Sourcing Everywhere
**Description**: Áp dụng ES cho mọi service vì "best practice"

**Example**:
```java
// ❌ BAD: Event Sourcing cho User Profile
UserProfileUpdatedEvent event = new UserProfileUpdatedEvent(
    userId, newEmail, newPhone, newAddress, newPreferences
);
eventStore.save(event);  // Overkill for simple updates

// ✅ GOOD: Simple UPDATE
userRepository.update(userId, updateRequest);
redisCache.delete("user:" + userId);  // Invalidate cache
```

**Why Bad**: 
- 60% more code
- 100% more storage
- No real benefit (users không query profile history)

---

### 🚫 Anti-Pattern 2: CRUD for Financial Transactions
**Description**: Dùng direct DB updates cho money movement

**Example**:
```java
// ❌ BAD: Direct wallet update
@Transactional
public void processPayment(Payment payment) {
    Wallet wallet = walletRepo.findById(payment.getUserId());
    wallet.setBalance(wallet.getBalance() - payment.getAmount());
    walletRepo.save(wallet);  // No audit trail!
}

// ✅ GOOD: Event Sourcing
public void processPayment(Payment payment) {
    WalletDebitedEvent debit = new WalletDebitedEvent(
        wallet.getId(), payment.getAmount(), payment.getId()
    );
    WalletCreditedEvent credit = new WalletCreditedEvent(
        escrowWallet.getId(), payment.getAmount(), payment.getId()
    );
    // Double-entry bookkeeping with complete audit trail
    eventStore.saveBatch(Arrays.asList(debit, credit));
}
```

**Why Bad**: 
- No audit trail for disputes
- Cannot reconstruct balance at any point in time
- Regulatory non-compliance (PCI-DSS, SOC2)

---

### 🚫 Anti-Pattern 3: Ignoring Long-Running Processes
**Description**: Dùng simple CRUD cho workflows kéo dài

**Example**:
```java
// ❌ BAD: Simple status field for shipping
@Entity
public class Shipment {
    private String status;  // "PENDING", "SHIPPED", "DELIVERED"
    
    // No handling for:
    // - Webhook timeouts
    // - Duplicate webhooks
    // - Out-of-order events
}

// ✅ GOOD: Event Sourcing + Temporal
@Workflow
public void shipmentWorkflow(ShipmentRequest req) {
    // Activity 1: Create carrier shipment
    activities.createCarrierShipment(req);
    
    // Wait for pickup (24h timeout)
    boolean pickedUp = Workflow.await(
        Duration.ofHours(24),
        () -> state.getStatus() == PICKED_UP
    );
    
    if (!pickedUp) {
        activities.cancelShipment(req.getShipmentId());
    }
    
    // Continue workflow...
}
```

**Why Bad**:
- Cannot handle timeouts declaratively
- Duplicate webhooks corrupt state
- Out-of-order events cause inconsistency
- No visibility into workflow progress

---

### 🚫 Anti-Pattern 4: CQRS for Write-Heavy Workloads
**Description**: Separate read models khi write volume rất cao

**Example**:
```python
# ❌ BAD: CQRS for real-time analytics ingestion
def track_page_view(event):
    # Write to event store
    event_store.save(PageViewEvent(event))
    # Sync to read model (bottleneck!)
    analytics_view.update(aggregate_stats(event))  # Too slow!

# ✅ GOOD: Append-only with batch aggregation
def track_page_view(event):
    # Write to TimescaleDB hypertable (optimized for writes)
    timescale.insert(event)
    # Continuous aggregates compute stats every hour
```

**Why Bad**:
- Read model sync overhead > performance gains
- Write latency increases
- Complex coordination logic

---

## Cost-Benefit Analysis

### Event Sourcing

**Costs**:
- 📈 **Development**: +60% code complexity
- 💾 **Storage**: +100-200% (events never deleted)
- 🕐 **Query Latency**: +10-20ms (rebuild from events)
- 🧠 **Learning Curve**: High (team training needed)

**Benefits**:
- ✅ Complete audit trail (compliance)
- ✅ Time-travel debugging
- ✅ Event replay for analytics
- ✅ Append-only (no UPDATE/DELETE anomalies)

**ROI Threshold**: Cần ít nhất 2/4 benefits để justify costs

---

### CQRS

**Costs**:
- 📈 **Development**: +30% code (sync logic)
- 💾 **Storage**: +50% (denormalized views)
- 🔧 **Complexity**: Eventual consistency handling
- 🧠 **Learning Curve**: Medium

**Benefits**:
- ✅ Read performance (sub-100ms queries)
- ✅ Independent scaling (read replicas)
- ✅ Multiple projections (customer, admin, analytics)
- ✅ Simpler read-side code (no complex JOINs)

**ROI Threshold**: Read:Write ratio > 10:1 hoặc complex queries

---

### Saga Pattern (Temporal)

**Costs**:
- 📈 **Development**: +80% code (activities, compensation)
- 🏗️ **Infrastructure**: Temporal cluster (3+ nodes)
- 💰 **Operational**: Additional monitoring, debugging
- 🧠 **Learning Curve**: High

**Benefits**:
- ✅ Distributed transaction coordination
- ✅ Automatic retry, timeout handling
- ✅ Compensation logic (rollback)
- ✅ Visibility (Temporal UI)
- ✅ Durable execution (survives restarts)

**ROI Threshold**: Multi-service transactions VỚI compensation logic

---

## Decision Checklist

### Before Choosing Event Sourcing

- [ ] Do you have legal/compliance requirements for audit trail?
- [ ] Is data financial or high-value (money, inventory, orders)?
- [ ] Do you need to reconstruct state at any point in time?
- [ ] Is write volume manageable (< 1000 events/sec per aggregate)?
- [ ] Have you considered maintenance costs (event schema evolution)?

**If checked < 2 items**: Probably don't need Event Sourcing

---

### Before Choosing CQRS

- [ ] Is read:write ratio > 10:1?
- [ ] Are read queries significantly different from write model?
- [ ] Do you need multiple read projections (customer, admin, analytics)?
- [ ] Can you handle eventual consistency (read lag 1-5 seconds)?
- [ ] Is caching insufficient to solve read performance?

**If checked < 3 items**: Simple CRUD + caching likely sufficient

---

### Before Choosing Saga Pattern

- [ ] Does operation span 2+ microservices?
- [ ] Is process long-running (> 5 minutes)?
- [ ] Do you need compensation logic (rollback on failure)?
- [ ] Are there async dependencies (webhooks, external APIs)?
- [ ] Can you justify Temporal infrastructure costs?

**If checked < 3 items**: Direct HTTP calls with retry likely sufficient

---

## Pattern Evolution Path

### Phase 1: Start Simple
```
All new services: Simple CRUD + Redis cache + Kafka events
```

### Phase 2: Add CQRS When Needed
```
IF read performance becomes bottleneck
THEN add materialized views or Elasticsearch
```

### Phase 3: Add Event Sourcing When Required
```
IF compliance/audit requirements emerge
OR financial transactions added
THEN migrate to Event Sourcing
```

### Phase 4: Add Saga If Necessary
```
IF distributed transactions with compensation
THEN introduce Temporal workflows
```

**Key Principle**: **START SIMPLE, EVOLVE WHEN NEEDED**

---

## References

- [Microservices Patterns by Chris Richardson](https://microservices.io/patterns/)
- [Event Sourcing Pattern](https://martinfowler.com/eaaDev/EventSourcing.html)
- [CQRS Journey by Microsoft](https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10))
- [Temporal Documentation](https://docs.temporal.io/)
