# Implementation Roadmap - Phased Development Plan

**Duration**: 8 months (32 weeks)  
**Team Size**: 8-10 developers  
**Parallel Tracks**: 2-3 services simultaneously

## Overview

Roadmap được chia thành 4 phases, mỗi phase focus vào một nhóm services với dependencies rõ ràng. Services trong cùng phase có thể develop parallel, các phase tiếp theo depend on output của phase trước.

## Phase 1: Foundation (Weeks 1-8)

**Goal**: Core infrastructure + identity + reference implementations

**Deliverables**:
- Authentication & authorization functional
- User management system
- Shopping cart basic flow
- Order Service reference (Java Event Sourcing pattern)
- Infrastructure setup (PostgreSQL, Redis, Kafka, Temporal)

---

### 1.1 Infrastructure Setup (Week 1-2)

**Tasks**:
- [ ] Setup PostgreSQL clusters
  - [ ] Main databases (per service)
  - [ ] Event Store schemas
  - [ ] Replication configuration
- [ ] Setup Redis cluster
  - [ ] Master-replica topology
  - [ ] Keyspace design
- [ ] Setup Kafka cluster
  - [ ] Topics creation (auto-create disabled)
  - [ ] Retention policies
  - [ ] Schema registry (optional)
- [ ] Setup Temporal cluster
  - [ ] 3-node cluster for HA
  - [ ] Namespace creation
  - [ ] Elasticsearch for visibility
- [ ] CI/CD pipelines
  - [ ] GitHub Actions / GitLab CI
  - [ ] Docker image builds
  - [ ] Kubernetes deployment

**Team**: 2 DevOps engineers

---

### 1.2 Auth Service - Complete Implementation (Week 2-3)

**Status**: ✅ Already implemented (Go)  
**Location**: [`go/services/auth-service`](../go/services/auth-service)

**Tasks** (Polish & Deploy):
- [x] Core authentication (login, logout, token refresh) ✅
- [x] Session management with Redis ✅
- [x] Rate limiting & IP blocking ✅
- [ ] Integration testing
- [ ] Load testing (target: 1000 req/sec)
- [ ] Production deployment
- [ ] Monitoring setup (Prometheus, Grafana)

**Dependencies**: Infrastructure setup  
**Team**: 1 Go developer + 1 QA

---

### 1.3 User Service (Week 3-5)

**Pattern**: Tier 1 (Simple CRUD)  
**Language**: Go

**Tasks**:
- [ ] Database schema setup
  - [ ] `users` table
  - [ ] `user_addresses` table
  - [ ] `user_preferences` table
  - [ ] `restriction_status_history` table
- [ ] CRUD endpoints
  - [ ] GET /users/{id}
  - [ ] PUT /users/{id}/profile
  - [ ] POST /users/{id}/addresses
  - [ ] PUT /users/{id}/preferences
- [ ] Restriction state machine
  - [ ] NONE → WARNING → LIMITED → SUSPENDED → BANNED
  - [ ] Admin endpoints for restriction changes
- [ ] Redis caching layer
  - [ ] Cache user profiles (TTL: 1h)
  - [ ] Cache invalidation on updates
- [ ] Event publishing
  - [ ] `UserDeactivatedEvent` → Cascade to Auth, Seller
  - [ ] `UserAddressChangedEvent` → Update shipping estimates
- [ ] Integration with Auth Service (user lookup)

**Dependencies**: Auth Service deployed  
**Team**: 2 Go developers

---

### 1.4 Cart Service (Week 5-7)

**Pattern**: Tier 1 (Simple CRUD)  
**Language**: Go  
**Current Status**: ⚠️ Stub exists, needs completion

**Tasks**:
- [ ] Complete database schema
  - [ ] `cart_items` table (PostgreSQL backup)
  - [ ] Redis schema design
- [ ] Redis-first architecture
  - [ ] Write to Redis immediately
  - [ ] Background sync to PostgreSQL (after 5min inactivity)
  - [ ] Read from Redis first, fallback PostgreSQL
- [ ] Core operations
  - [ ] POST /cart/items (add item)
  - [ ] DELETE /cart/items/{id} (remove item)
  - [ ] PUT /cart/items/{id}/quantity
  - [ ] GET /cart (get cart)
- [ ] Guest cart merging (login flow)
  - [ ] Merge guest cart → user cart on login
  - [ ] Conflict resolution (same product in both)
- [ ] Price snapshot mechanism
  - [ ] Cache product price at time of add
  - [ ] Price update notifications
- [ ] Event publishing
  - [ ] `CartAbandonedEvent` (after 24h inactivity)
  - [ ] `CartConvertedEvent` (when order created)

**Dependencies**: Product Service API (stub for testing)  
**Team**: 1 Go developer

---

### 1.5 Order Service - Polish Reference Implementation (Week 6-8)

**Pattern**: Tier 4 (Full Stack - ES + CQRS + Saga)  
**Language**: Java/Spring Boot  
**Current Status**: ✅ Reference implementation exists

**Tasks**:
- [ ] Review existing implementation
  - [ ] Event Sourcing patterns
  - [ ] CQRS read models
  - [ ] Temporal saga workflows
- [ ] Database migrations
  - [ ] `orders` table
  - [ ] `order_items` table
  - [ ] `order_status_history` table
  - [ ] `event_store` table
- [ ] Saga workflow refinement
  - [ ] Reserve Inventory activity
  - [ ] Process Payment activity
  - [ ] Create Shipment activity
  - [ ] Compensation logic testing
- [ ] Email confirmation flow
  - [ ] OTP generation
  - [ ] 15-min timeout
  - [ ] Auto-cancel if not confirmed
- [ ] 10-state machine validation
  - [ ] `OrderStateMachine.canTransition()`
  - [ ] Invalid transition handling
- [ ] CQRS read models
  - [ ] Customer order history view
  - [ ] Seller sub-orders view
  - [ ] Analytics metrics view
- [ ] Integration tests
  - [ ] Happy path (full saga success)
  - [ ] Compensation paths (payment fails, etc.)
- [ ] Documentation
  - [ ] Architecture diagrams
  - [ ] API documentation
  - [ ] Developer guide

**Dependencies**: Inventory, Payment, Shipping Service APIs (stubs OK for now)  
**Team**: 2 Java developers + 1 architect

---

### Phase 1 Success Criteria

- [ ] Auth Service handling 1000 req/sec
- [ ] User Service integrated with Auth
- [ ] Cart Service with Redis performance (<50ms)
- [ ] Order Service reference implementation deployed
- [ ] All services with monitoring dashboards
- [ ] CI/CD pipelines functional

---

## Phase 2: Commerce Core (Weeks 9-16)

**Goal**: Enable end-to-end purchase flow

**Deliverables**:
- Product catalog with search
- Inventory management with reservations
- Payment processing (multi-gateway)
- Shipping integration with carriers

---

### 2.1 Product Service (Week 9-12)

**Pattern**: Tier 2 (CQRS Only) ⬅️ Downgraded from ES  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `products` table
  - [ ] `product_skus` table
  - [ ] `product_attributes` table
  - [ ] `product_price_history` table (audit)
  - [ ] `product_media` table
- [ ] Write model (normalized)
  - [ ] POST /products (seller creates product)
  - [ ] PUT /products/{id} (update product)
  - [ ] PUT /products/{id}/price (with audit log)
  - [ ] DELETE /products/{id} (soft delete)
- [ ] Read model (denormalized)
  - [ ] Materialized view: `products_catalog_view`
  - [ ] Includes: seller_shop_name, avg_rating, review_count
  - [ ] Refresh strategy: On-demand + scheduled (every 5 min)
- [ ] Price history audit
  - [ ] Trigger: Auto-log on price update
  - [ ] Admin endpoint: GET /products/{id}/price-history
- [ ] Category hierarchy
  - [ ] Tree structure with `parent_id`
  - [ ] Breadcrumb precomputation
- [ ] Product variants (SKUs)
  - [ ] Size, color, etc.
  - [ ] Individual pricing & stock per variant
- [ ] Event publishing
  - [ ] `ProductCreatedEvent` → Search Service (index)
  - [ ] `ProductPriceChangedEvent` → Cart Service (notify)
  - [ ] `ProductDeactivatedEvent` → Search Service (remove)
- [ ] Integration with Seller Service
  - [ ] Validate seller is VERIFIED before product creation
  - [ ] Enrich product with shop info

**Dependencies**: Seller Service (can stub initially)  
**Team**: 2 Java developers

---

### 2.2 Inventory Service (Week 10-13)

**Pattern**: Tier 3 (Event Sourcing)  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `inventory_stock` table
  - [ ] `stock_reservations` table
  - [ ] `stock_adjustments` table
  - [ ] `event_store` table (shipping-specific)
- [ ] Event Sourcing setup
  - [ ] Event types: `StockReceivedEvent`, `StockReservedEvent`, etc.
  - [ ] Event Store repository
  - [ ] Aggregate: `StockAggregate`
- [ ] Core operations
  - [ ] POST /inventory/receive (stock receipt)
  - [ ] POST /inventory/reserve (order reservation)
  - [ ] DELETE /inventory/reservations/{id} (release)
  - [ ] PUT /inventory/adjust (manual adjustment)
- [ ] Reservation expiry
  - [ ] Temporal workflow: Auto-release after 15 min
  - [ ] Check if order paid before release
- [ ] Multi-warehouse support
  - [ ] Virtual stock aggregation
  - [ ] Warehouse selection logic (closest to customer)
- [ ] CQRS read model
  - [ ] Materialized view: `inventory_stock_summary`
  - [ ] Columns: sku_id, physical, reserved, available
- [ ] Event publishing
  - [ ] `StockReservedEvent` → Order Service
  - [ ] `ReservationReleasedEvent` → Order Service
  - [ ] `StockLowEvent` → Notification Service (alert seller)
- [ ] Fraud detection queries
  - [ ] Detect mass reservations from same IP
  - [ ] Alert on unusual stock adjustments

**Dependencies**: Product Service (SKU IDs)  
**Team**: 2 Java developers

---

### 2.3 Payment Service (Week 11-14)

**Pattern**: Tier 4 (Full Stack - ES + CQRS + Saga)  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `payment_transactions` table
  - [ ] `user_wallets` table
  - [ ] `wallet_transactions` table (double-entry)
  - [ ] `seller_payouts` table
  - [ ] `event_store` table
- [ ] Event Sourcing setup
  - [ ] Event types: `PaymentInitiatedEvent`, `PaymentCapturedEvent`, etc.
  - [ ] Double-entry bookkeeping (debit + credit events)
  - [ ] Event Store repository
- [ ] Gateway integrations
  - [ ] VNPay adapter
  - [ ] MoMo adapter
  - [ ] Stripe adapter (future)
- [ ] Payment flow
  - [ ] POST /payments/initiate (create payment intent)
  - [ ] Gateway redirect
  - [ ] Webhook handler (payment confirmation)
  - [ ] POST /payments/{id}/capture (capture authorized payment)
- [ ] Wallet management
  - [ ] POST /wallets (create user wallet)
  - [ ] GET /wallets/{userId}/balance
  - [ ] POST /wallets/{id}/credit (top-up)
  - [ ] POST /wallets/{id}/debit (purchase)
- [ ] Seller settlements
  - [ ] Daily batch job: Calculate pending payouts
  - [ ] 7-day hold period
  - [ ] POST /payouts (process payout to seller bank)
- [ ] CQRS read models
  - [ ] Materialized view: `user_wallet_balance`
  - [ ] View: `seller_pending_payouts`
- [ ] Saga activities (for Order Service)
  - [ ] `ProcessPaymentActivity`
  - [ ] `RefundPaymentActivity` (compensation)
- [ ] Idempotency
  - [ ] `idempotency_key` for all payment operations
  - [ ] Prevent double charges
- [ ] PCI-DSS compliance
  - [ ] Tokenize card data (never store raw)
  - [ ] Secure communication (TLS 1.3)
  - [ ] Audit logging

**Dependencies**: Order Service (saga integration)  
**Team**: 2 Java developers + 1 security specialist

---

### 2.4 Shipping Service (Week 12-15)

**Pattern**: Tier 3 (Event Sourcing + Temporal) ⬅️ Upgraded from CRUD  
**Language**: Go (or Java - to be decided)

**Tasks**:
- [ ] Database schema
  - [ ] `shipments` table
  - [ ] `carrier_rates` table
  - [ ] `shipping_zones` table
  - [ ] `shipping_event_store` table (with idempotency)
- [ ] Event Sourcing setup
  - [ ] Event types: `ShipmentCreatedEvent`, `ShipmentPickedUpEvent`, etc.
  - [ ] Idempotency index: `(carrier, carrier_event_id)`
- [ ] Carrier integrations
  - [ ] GHN API adapter
  - [ ] GHTK API adapter
  - [ ] Viettel Post API adapter
- [ ] Shipment creation
  - [ ] POST /shipments (create with carrier API call)
  - [ ] Rate calculation (based on weight, distance, zone)
  - [ ] Carrier selection logic
- [ ] Webhook handlers (idempotent)
  - [ ] POST /webhooks/ghn/status
  - [ ] POST /webhooks/ghtk/status
  - [ ] POST /webhooks/viettel/status
  - [ ] Check duplicate webhook (via carrier_event_id)
  - [ ] Validate state transition
  - [ ] Save event + signal Temporal workflow
- [ ] Temporal workflow
  - [ ] `ShipmentWorkflow`: Long-running (5-7 days)
  - [ ] Wait for pickup (24h timeout → cancel)
  - [ ] Wait for delivery (7 days timeout → investigate)
  - [ ] Handle delivery failures (retry logic)
- [ ] State machine validation
  - [ ] `ShipmentStateMachine.canTransition()`
  - [ ] Prevent out-of-order transitions
- [ ] CQRS read models
  - [ ] View: `shipment_status_view` (current status)
  - [ ] View: `shipment_history_view` (timeline for customer)
- [ ] Customer tracking
  - [ ] GET /shipments/{id}/tracking
  - [ ] Returns full event history
- [ ] Event publishing
  - [ ] `ShipmentStatusUpdatedEvent` → Order Service

**Dependencies**: Order Service (shipment creation triggered by order)  
**Team**: 2 developers (Go or Java)

---

### Phase 2 Success Criteria

- [ ] Product catalog with 1000+ products
- [ ] Inventory reservations working (15-min expiry)
- [ ] Payment processing (VNPay + MoMo)
- [ ] Shipping webhooks handling duplicates correctly
- [ ] End-to-end purchase flow (Cart → Order → Payment → Shipment)
- [ ] Saga compensation tested (payment fails → inventory released)

---

## Phase 3: Seller & Engagement (Weeks 17-24)

**Goal**: Marketplace features + customer feedback loop

**Deliverables**:
- Seller onboarding & verification
- Promotional campaigns with budget tracking
- Review system with moderation
- Refund workflow with approval chain

---

### 3.1 Seller Service (Week 17-19)

**Pattern**: Tier 2 (CQRS Only)  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `sellers` table
  - [ ] `seller_verification` table (KYC)
  - [ ] `seller_staff` table
  - [ ] `seller_policies` table (with AI embeddings)
- [ ] Write model
  - [ ] POST /sellers (apply to become seller)
  - [ ] PUT /sellers/{id}/verification (upload documents)
  - [ ] POST /sellers/{id}/staff (add staff member)
  - [ ] PUT /sellers/{id}/policies (update policies)
- [ ] Verification workflow
  - [ ] PENDING → UNDER_REVIEW → APPROVED → ACTIVE
  - [ ] Admin panel endpoints
  - [ ] Document validation (ID, tax certificate)
- [ ] Read model
  - [ ] Materialized view: `seller_shop_summary`
  - [ ] Denormalized: product_count, avg_rating, sales_volume
  - [ ] Cached in Redis
- [ ] Event-driven sync
  - [ ] Subscribe to `ProductCreatedEvent` → Update product_count
  - [ ] Subscribe to `ReviewApprovedEvent` → Recalculate avg_rating
- [ ] Event publishing
  - [ ] `SellerVerifiedEvent` → Product Service (enable products)
  - [ ] `SellerDeactivatedEvent` → Product Service (hide products)

**Dependencies**: User Service (seller account linkage)  
**Team**: 1 Java developer

---

### 3.2 Promotion Service (Week 18-21)

**Pattern**: Tier 3 (Event Sourcing)  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `promotions` table
  - [ ] `promotion_usage` table
  - [ ] `promotion_budgets` table
  - [ ] `event_store` table
- [ ] Event Sourcing setup
  - [ ] Event types: `PromotionCreatedEvent`, `PromotionUsedEvent`, etc.
  - [ ] Optimistic locking (`@Version` field)
- [ ] Drools rules engine
  - [ ] Rule definitions (DRL files)
  - [ ] Eligibility checks
  - [ ] Stacking logic (can combine promotions?)
- [ ] Core operations
  - [ ] POST /promotions (admin creates promotion)
  - [ ] POST /promotions/{id}/validate (check eligibility)
  - [ ] POST /promotions/{id}/apply (use promotion code)
  - [ ] GET /promotions/available (for user)
- [ ] Budget tracking
  - [ ] Prevent over-spending with optimistic locking
  - [ ] `PromotionBudgetExhaustedEvent` when depleted
- [ ] Fraud detection
  - [ ] Query event store for abuse patterns
  - [ ] Same IP, multiple codes → Suspicious
  - [ ] Mass usage in short time → Alert
- [ ] CQRS read model
  - [ ] Redis cache: Eligible promotions per user
  - [ ] TTL: 5 min
- [ ] Integration with Order Service
  - [ ] Order Service calls `POST /promotions/validate`
  - [ ] Apply discount to order total
  - [ ] Record usage in event store

**Dependencies**: None (standalone)  
**Team**: 2 Java developers

---

### 3.3 Review Service (Week 20-22)

**Pattern**: Tier 2 (CQRS Only)  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `product_reviews` table
  - [ ] `review_media` table (photos, videos)
  - [ ] `review_votes` table (helpful votes)
  - [ ] `review_edit_history` table
- [ ] Write model
  - [ ] POST /reviews (customer writes review)
  - [ ] PUT /reviews/{id} (edit review)
  - [ ] POST /reviews/{id}/vote (helpful vote)
  - [ ] POST /reviews/{id}/report (report abuse)
- [ ] Moderation workflow
  - [ ] PENDING → APPROVED / REJECTED
  - [ ] Admin moderation panel
  - [ ] Auto-approve for verified buyers (configurable)
- [ ] Read model
  - [ ] Materialized view: `product_review_stats`
  - [ ] Aggregates: avg_rating, total_reviews, rating_distribution
  - [ ] Cached in Redis (TTL: 5 min)
- [ ] Media handling
  - [ ] Upload to S3 / Azure Blob Storage
  - [ ] Image resizing (thumbnails)
  - [ ] Video transcoding (if needed)
- [ ] Seller responses
  - [ ] POST /reviews/{id}/responses (seller replies to review)
- [ ] Event publishing
  - [ ] `ReviewApprovedEvent` → Product Service (update rating)
  - [ ] `ReviewApprovedEvent` → Seller Service (update seller avg_rating)

**Dependencies**: Order Service (verify purchase before review), Product Service  
**Team**: 1 Java developer

---

### 3.4 Refund Service (Week 21-24)

**Pattern**: Tier 3 (Event Sourcing + Saga)  
**Language**: Java/Spring Boot

**Tasks**:
- [ ] Database schema
  - [ ] `refunds` table
  - [ ] `refund_items` table (partial refunds)
  - [ ] `refund_approvals` table (approval chain)
  - [ ] `event_store` table
- [ ] Event Sourcing setup
  - [ ] Event types: `RefundRequestedEvent`, `RefundApprovedEvent`, etc.
- [ ] Refund request flow
  - [ ] POST /refunds (customer requests refund)
  - [ ] Upload evidence (photos, reason)
  - [ ] Auto-create approval workflow
- [ ] Approval chain
  - [ ] CS Agent → Manager → Finance (for large amounts)
  - [ ] PUT /refunds/{id}/approve (each level)
  - [ ] PUT /refunds/{id}/reject
- [ ] Refund types
  - [ ] Full refund (all items)
  - [ ] Partial refund (specific items)
  - [ ] Re-refund (if first refund incomplete)
- [ ] Temporal saga
  - [ ] Activity 1: Refund payment
  - [ ] Activity 2: Restock inventory (if return item)
  - [ ] Activity 3: Update order status
  - [ ] Activity 4: Notify customer
  - [ ] Compensation: Reverse refund if error
- [ ] CQRS read model
  - [ ] View: `pending_refunds_view` (for admin dashboard)
  - [ ] Order by pending_days DESC
- [ ] Integration
  - [ ] Payment Service: Refund payment
  - [ ] Inventory Service: Restock items
  - [ ] Order Service: Update order status to REFUNDED

**Dependencies**: Order Service, Payment Service, Inventory Service  
**Team**: 2 Java developers

---

### Phase 3 Success Criteria

- [ ] Seller onboarding with KYC verification
- [ ] Promotion codes working (budget tracking, fraud detection)
- [ ] Review system with moderation (1000+ reviews)
- [ ] Refund flow with approval chain tested
- [ ] Saga compensation for refund errors working

---

## Phase 4: Intelligence & Scale (Weeks 25-32)

**Goal**: Analytics, AI features, real-time communication

**Deliverables**:
- Full-text product search
- Multi-channel notifications
- Real-time customer support chat
- Business intelligence dashboards
- AI-powered chatbot

---

### 4.1 Search Service (Week 25-27)

**Pattern**: Tier 1 (Simple CRUD - Elasticsearch)  
**Language**: Go

**Tasks**:
- [ ] Elasticsearch setup
  - [ ] Index: `products`
  - [ ] Mappings: text fields, facets, vectors (if similarity search)
- [ ] Indexing pipeline
  - [ ] Subscribe to `product-events` Kafka topic
  - [ ] On `ProductCreatedEvent`: Index product
  - [ ] On `ProductUpdatedEvent`: Update index
  - [ ] On `ProductDeactivatedEvent`: Remove from index
- [ ] Search API
  - [ ] GET /search?q={query} (full-text search)
  - [ ] Faceted filtering (category, price range, rating)
  - [ ] Autocomplete (ngrams)
  - [ ] Pagination
- [ ] Ranking algorithm
  - [ ] Text relevance (BM25)
  - [ ] Popularity boost (sales volume)
  - [ ] Personalization (user preferences - future)
- [ ] Reindexing
  - [ ] Bulk reindex from Product Service
  - [ ] Zero-downtime reindex (alias switching)

**Dependencies**: Product Service (initial data load)  
**Team**: 1 Go developer

---

### 4.2 Notification Service (Week 26-28)

**Pattern**: Tier 1 (Simple CRUD)  
**Language**: Go

**Tasks**:
- [ ] Database schema
  - [ ] `notification_queue` table
  - [ ] `notification_templates` table
  - [ ] `notification_providers` table
- [ ] Template engine
  - [ ] Go templates for email/SMS/push
  - [ ] Variable substitution (user_name, order_id, etc.)
- [ ] Multi-channel support
  - [ ] Email (SendGrid, AWS SES)
  - [ ] SMS (Twilio, generic gateway)
  - [ ] Push notifications (Firebase Cloud Messaging)
  - [ ] In-app notifications (WebSocket)
- [ ] Event consumption
  - [ ] Subscribe to ALL service events
  - [ ] Match event to template
  - [ ] Queue notification
- [ ] Delivery workers
  - [ ] Background goroutines polling queue
  - [ ] Send via provider
  - [ ] Update delivery status
- [ ] Rate limiting
  - [ ] Per-user limits (max 10 emails/day)
  - [ ] Provider limits (API quotas)
- [ ] Retry logic
  - [ ] Exponential backoff
  - [ ] Dead letter queue (DLQ) for failures

**Dependencies**: All services (event consumption)  
**Team**: 1 Go developer

---

### 4.3 Chat Service (Week 27-29)

**Pattern**: Tier 1 (Simple CRUD - ScyllaDB)  
**Language**: Go

**Tasks**:
- [ ] ScyllaDB setup
  - [ ] Keyspace creation
  - [ ] Table: `messages` (partition by room_id)
- [ ] WebSocket server
  - [ ] Connection management
  - [ ] Message broadcasting within room
  - [ ] Online presence tracking
- [ ] REST API
  - [ ] POST /rooms (create chat room)
  - [ ] POST /rooms/{id}/messages (send message)
  - [ ] GET /rooms/{id}/messages (paginated history)
  - [ ] GET /rooms/{id}/members (room participants)
- [ ] Room types
  - [ ] Direct message (user ↔ user)
  - [ ] Support chat (user ↔ seller)
  - [ ] Group chat (future)
- [ ] Message features
  - [ ] Text messages
  - [ ] Image attachments (S3 upload)
  - [ ] Read receipts
  - [ ] Typing indicators
- [ ] Integration with Notification Service
  - [ ] Publish `MessageReceivedEvent` for offline users
  - [ ] Push notification

**Dependencies**: User Service (user lookup), Notification Service  
**Team**: 1 Go developer

---

### 4.4 Analytics Service (Week 28-30)

**Pattern**: Tier 1 (Simple CRUD - TimescaleDB)  
**Language**: Python

**Tasks**:
- [ ] TimescaleDB setup
  - [ ] Hypertable: `behavior_events`
  - [ ] Continuous aggregates (hourly, daily)
- [ ] Event ingestion
  - [ ] Subscribe to all Kafka topics
  - [ ] Insert into TimescaleDB
  - [ ] Auto-compression for old data
- [ ] Metrics tracked
  - [ ] Page views, product views
  - [ ] Cart add/remove actions
  - [ ] Order conversion funnel
  - [ ] Payment success/failure rates
  - [ ] User behavior sequences
- [ ] Fraud detection
  - [ ] Anomaly detection (sudden order volume spike)
  - [ ] Risk scoring (user behavior patterns)
  - [ ] Alert on suspicious activity
- [ ] Dashboards (Grafana)
  - [ ] Business metrics (revenue, orders, users)
  - [ ] Operational metrics (error rates, latency)
  - [ ] Seller metrics (sales per seller)
- [ ] Data export
  - [ ] Daily CSV export to S3 (for data warehouse)

**Dependencies**: All services (event consumption)  
**Team**: 1 Python developer + 1 data analyst

---

### 4.5 AI Agent Service (Week 30-32)

**Pattern**: Tier 2 (CQRS Only)  
**Language**: Python

**Tasks**:
- [ ] Database schema (PostgreSQL + pgvector)
  - [ ] `knowledge_chunks` table (with embeddings)
  - [ ] `conversations` table (chat history)
- [ ] Write side (Knowledge base building)
  - [ ] Subscribe to events (Product, Order, Review)
  - [ ] LangChain text splitter
  - [ ] OpenAI embeddings generation
  - [ ] Store chunks in pgvector
- [ ] Read side (RAG retrieval)
  - [ ] User query → OpenAI embedding
  - [ ] Vector similarity search (pgvector)
  - [ ] Top 5 chunks retrieved
  - [ ] LLM context construction
- [ ] Chatbot API
  - [ ] POST /chat (send message)
  - [ ] OpenAI GPT-4 inference
  - [ ] Streaming response (SSE)
- [ ] Intent detection
  - [ ] Order tracking
  - [ ] Product recommendations
  - [ ] Refund requests
- [ ] Handoff to human agent
  - [ ] If intent confidence < 0.7
  - [ ] Create Chat Service room with support agent
- [ ] Fine-tuning (future)
  - [ ] Collect conversation data
  - [ ] Label good/bad responses
  - [ ] Fine-tune GPT-4 on domain data

**Dependencies**: Product Service, Order Service (knowledge base data)  
**Team**: 1 Python ML engineer

---

### Phase 4 Success Criteria

- [ ] Search Service handling 1000 queries/sec
- [ ] Notification Service delivering 10K notifications/day
- [ ] Chat Service with 100 concurrent connections
- [ ] Analytics dashboards with real-time metrics
- [ ] AI chatbot answering 80% of customer queries without human

---

## Parallel Development Strategy

### Week-by-Week Breakdown

| Week | Team A (Go) | Team B (Java) | Team C (Infra/QA) |
|------|-------------|---------------|-------------------|
| 1-2 | Infrastructure | Infrastructure | Setup CI/CD |
| 3-4 | User Service | - | Auth Service testing |
| 5-6 | Cart Service | Order Service polish | Integration tests |
| 7-8 | - | Order Service deploy | Load testing |
| 9-10 | - | Product Service | - |
| 11-12 | Shipping Service | Inventory + Payment | Payment security audit |
| 13-14 | Shipping Service | Payment Service | Integration tests |
| 15-16 | - | Payment Service | Saga testing |
| 17-18 | - | Seller Service | - |
| 19-20 | - | Promotion Service | Drools testing |
| 21-22 | - | Review Service | - |
| 23-24 | - | Refund Service | Saga testing |
| 25-26 | Search Service | - | Elasticsearch tuning |
| 27-28 | Notification + Chat | - | WebSocket testing |
| 29-30 | - | - | Analytics dashboards |
| 31-32 | - | AI Agent (Python) | End-to-end testing |

### Team Composition

**Team A (Go Services)**:
- 2 Senior Go developers
- Focus: High-performance CRUD services

**Team B (Java Services)**:
- 3 Senior Java developers
- Focus: Event Sourcing, CQRS, complex business logic

**Team C (Infrastructure & QA)**:
- 1 DevOps engineer
- 1 QA automation engineer
- 1 Performance engineer

**Team D (Python/ML)**:
- 1 ML engineer (joins Week 30)
- Focus: Analytics, AI Agent

**Total**: 8-9 people

---

## Risk Mitigation

### High-Risk Items

#### Risk 1: Temporal Learning Curve
**Probability**: Medium  
**Impact**: High (delays Phase 2-3)

**Mitigation**:
- Week 1-2: Team training on Temporal
- Week 6-8: Order Service reference as learning material
- Dedicated architect support during saga development

#### Risk 2: Payment Gateway Integration
**Probability**: High  
**Impact**: High (blocks Phase 2)

**Mitigation**:
- Early sandbox testing (Week 1)
- Fallback to single gateway (VNPay only) if delays
- External consultant for VNPay integration

#### Risk 3: Shipping Webhook Reliability
**Probability**: Medium  
**Impact**: Medium (customer complaints)

**Mitigation**:
- Comprehensive idempotency testing (Week 14-15)
- Manual tracking interface for stuck shipments
- SLA with carrier for webhook guarantees

#### Risk 4: Event Store Performance
**Probability**: Low  
**Impact**: Medium (slow queries)

**Mitigation**:
- Early performance testing (Week 10)
- Partition strategy defined upfront
- Snapshot mechanism for large aggregates

---

## Success Metrics

### Phase 1 (Foundation)
- ✅ Auth Service: 1000 req/sec, p99 < 100ms
- ✅ User Service: 500 req/sec, cache hit rate > 80%
- ✅ Cart Service: Redis latency < 10ms
- ✅ Order Service: Saga success rate > 99%

### Phase 2 (Commerce Core)
- ✅ Product Service: 10K products added
- ✅ Inventory: Reservation success rate > 99.9%
- ✅ Payment: Transaction success rate > 95%
- ✅ Shipping: Webhook processing < 1s

### Phase 3 (Seller & Engagement)
- ✅ Seller Service: 100 sellers verified
- ✅ Promotion: Budget tracking 100% accurate
- ✅ Review Service: 1000 reviews moderated
- ✅ Refund: Approval workflow < 24h

### Phase 4 (Intelligence)
- ✅ Search: Query latency < 200ms
- ✅ Notification: Delivery rate > 98%
- ✅ Chat: Message latency < 100ms
- ✅ AI Agent: Query response < 3s

---

## Post-Launch Roadmap

### Month 9-12 (Optimization)
- Performance tuning
- Cost optimization (cloud resources)
- Database sharding (if needed)
- Advanced analytics (ML models)

### Month 13+ (Features)
- Recommendation engine
- Social features (share, follow)
- Seller analytics dashboard
- Mobile apps (iOS, Android)

---

## References

- [Architecture Overview](00-architecture-overview.md)
- [Service Classification](02-service-classification.md)
- [Decision Log](07-decision-log.md)
