# E-Commerce Platform — Documentation Index

> **Canonical source of truth:** [Service Contract Matrix](./_contracts/CONTRACT_MATRIX.md).
> Mọi thay đổi về contract giữa các service phải cập nhật file đó trước, sau đó mới lan ra các doc khác.

---

## 0. Master Contracts (SSOT) — `docs/_contracts/`

| File | Mô tả |
|---|---|
| [CONTRACT_MATRIX.md](./_contracts/CONTRACT_MATRIX.md) | Bảng SSOT cho mọi event/payload/ID/state machine/TTL/idempotency. **Bắt buộc đọc trước khi sửa bất kỳ service doc nào.** |
| [IDEMPOTENCY_GUIDELINES.md](./_contracts/IDEMPOTENCY_GUIDELINES.md) | Chuẩn chung về idempotency 2 lớp (Redis SETNX + DB Unique), key format, anti-pattern. |
| [OUTBOX_PATTERNS.md](./_contracts/OUTBOX_PATTERNS.md) | CDC Pattern (Debezium) là chuẩn, Polling đã deprecated, Direct-Produce chỉ cho runtime event ngắn. |
| [CHANGELOG.md](./_contracts/CHANGELOG.md) | Lịch sử mọi sync change theo phase. |

### ADRs (Architecture Decision Records)

| File | Service | Decision |
|---|---|---|
| [order/refund_voucher_policy.md](./order/refund_voucher_policy.md) | Order + Promotion | Voucher types: PLATFORM (no SHOP). FULL/PARTIAL refund allocation rule. Non-blocking budget refund. Phase 23: không restore voucher. |

---

## 1. Checkout Service (BFF/Stateless)

> Tài liệu 5 file thiết kế Checkout Service.

| File | Mô tả |
|---|---|
| [checkout/checkout_architecture_design.md](./checkout/checkout_architecture_design.md) | Kiến trúc tổng quan: BFF/Aggregator, chống giả mạo client, anti-tampering. |
| [checkout/checkout_workflow.md](./checkout/checkout_workflow.md) | Luồng API Preview + Submit, parallel orchestration pattern, waterfall price, optimization decision tree. |
| [checkout/checkout_caching_schema.md](./checkout/checkout_caching_schema.md) | Redis session schema, rate limit, distributed lock, response/payload cache. |
| [checkout/checkout_idempotency.md](./checkout/checkout_idempotency.md) | One-time-use token, Lua script, anti-tampering validation. |
| [checkout/checkout_security_analysis.md](./checkout/checkout_security_analysis.md) | Security analysis & session token lifecycle. |

### Checkout contracts chuẩn
- `POST /v1/checkout/preview` (Preview API) — owner: Checkout
- `POST /v1/checkout/submit` (Submit API) — owner: Checkout
- Cross-ref: nhận `POST /v1/orders/create-from-checkout` từ Order Service

---

## 2. Order Service (Orchestrator + Temporal Saga)

> Tài liệu 9 file thiết kế Order Service.

| File | Mô tả |
|---|---|
| [order/order_workflow.md](./order/order_workflow.md) | Luồng API (Get Order, Search, Cancel, Return) + Saga Checkout (Pre-Payment + Post-Payment) + Saga Refund. |
| [order/order_db_design.md](./order/order_db_design.md) | Schema PostgreSQL: orders, order_items, order_item_lines, order_promotions, outbox, idempotency tables, refund cases. |
| [order/order_state_machine.md](./order/order_state_machine.md) | Master Order FSM + Order Item Delivery FSM + Buyer Tab Precedence + Auto-Complete. |
| [order/order_events.md](./order/order_events.md) | Event catalog cho `t.order.events` (10 events) + `t.search.metrics.events`. |
| [order/order_workers.md](./order/order_workers.md) | Background workers: CDC pipeline, Metrics projector, Payment/Inventory consumers, Archive batcher. |
| [order/temporal_saga_design.md](./order/temporal_saga_design.md) | Temporal workflow design: `OrderCheckoutWorkflow`, `OrderRefundWorkflow`, `OrderLatePaymentRecoveryWorkflow`. |

### Order cross-refs
- `docs/order/order_search_design.md` (Phase 15 — Search/Filter trực tiếp PostgreSQL) — file mở rộng
- ~~`docs/cold_data_archiving_design.md`~~ — **đã xoá tại Phase 16** (bỏ cold data archiving, giữ toàn bộ data trong OLTP)

---

## 3. Payment Service (PG State + Outbox)

> Tài liệu 4 file thiết kế Payment Service + 1 ADR.

| File | Mô tả |
|---|---|
| [payment/payment_workflow.md](./payment/payment_workflow.md) | Luồng API Initiate/Webhook/Query/Refund + Async Webhook processing. |
| [payment/payment_db_design.md](./payment/payment_db_design.md) | Schema: payment_transactions, refund_transactions, webhook_logs, payment_status_history, outbox (CDC). |
| [payment/payment_state_machine.md](./payment/payment_state_machine.md) | Payment FSM + Refund FSM + Guard rules + Audit trail. |
| [payment/payment_events.md](./payment/payment_events.md) | Event catalog cho `t.payment.events` (9 events: 3 PAYMENT_* + 1 INITIATED + 4 REFUND_* + 1 REVERSED). |
| [payment/payment_workers.md](./payment/payment_workers.md) | Background workers: Outbox CDC, Webhook Processor, Expiration Scanner, Reconciliation, Refund Poller. |
| [payment/technology_choice.md](./payment/technology_choice.md) | Lý do chọn Java Spring Boot. |
| [payment/refund_boundary_decision.md](./payment/refund_boundary_decision.md) | ADR: giữ Refund trong Payment Service (Phase 1) vs tách (Phase 2). |

---

## 4. Inventory Service (Redis Lua + PG)

> Tài liệu 6 file thiết kế Inventory Service.

| File | Mô tả |
|---|---|
| [inventory/inventory_workflow.md](./inventory/inventory_workflow.md) | Luồng Reserve/Deduct/Release + Routing Options + Admin Restock + Return Restock. |
| [inventory/inventory_db_design.md](./inventory/inventory_db_design.md) | Schema: warehouses, physical_inventory, inventory_reservations, inventory_ledger, inventory_outbox (CDC). |
| [inventory/inventory_redis_design.md](./inventory/inventory_redis_design.md) | Redis keys + Lua scripts 1/2/3 + cross-slot constraints. |
| [inventory/inventory_events.md](./inventory/inventory_events.md) | Event catalog cho `t.inventory.events` (3 events: MUTATION, CHANGED, RETURN_RECEIVED_ALL). |
| [inventory/inventory_endpoints.md](./inventory/inventory_endpoints.md) | API endpoints: routing-options, restock, refund-expectations, admin/unlock-sku. |
| [inventory/inventory_workers.md](./inventory/inventory_workers.md) | Background workers: Ledger Worker, CDC outbox, Return Consumer, etc. |
| [inventory/smart_routing_engine.md](./inventory/smart_routing_engine.md) | Smart Routing algorithm + cache + plan expiry handling. |

---

## 4. Promotion Service (Caffeine L1 + Redis Lua + PG)

> Tài liệu 10 file thiết kế Promotion Service.

| File | Mô tả |
|---|---|
| [promotion/promotion_workflow.md](./promotion/promotion_workflow.md) | Workflows: Admin CRUD, Flash Sale Claim, Cart Calculate, Saga Checkout (Reserve/Confirm/Cancel), Refund, Voucher Discovery. |
| [promotion/promotion_db_design.md](./promotion/promotion_db_design.md) | Schema: campaigns, promotions, promotion_budgets, coupon_claims, user_coupon_wallets, coupon_usages, promotion_event_idempotency. |
| [promotion/promotion_state_machine.md](./promotion/promotion_state_machine.md) | State machine cho voucher lifecycle + discount sequencing waterfall. |
| [promotion/promotion_events.md](./promotion/promotion_events.md) | Event catalog: `t.promotion.markdown.events` (3 events), `t.promotion.coupon.events` (5 events), `t.promotion.budget.events` (1 event). |
| [promotion/redis_caching_design.md](./promotion/redis_caching_design.md) | L1/L2 cache + 5 Lua scripts (Claim, Reserve, Batch Reserve 2B, Confirm Redeem, Rollback, Refund Budget). |
| [promotion/promotion_workers.md](./promotion/promotion_workers.md) | Background workers: Order Events Consumer (Refund Flow), Auth Events Consumer, Product State Consumer, Flash Sale Claim, Rule Cache Warmer, Claim Sweeper, Reservation TTL Sweep, Wallet Expiry (Lazy Inline), Cleanup, Reconciliation, Markdown Expiry Scheduler. |
| [promotion/rule_engine_design.md](./promotion/rule_engine_design.md) | Rule Engine: AST JSONB + Visitor pattern + Discount logic. |
| [promotion/voucher_discovery_api.md](./promotion/voucher_discovery_api.md) | Voucher Discovery API: voucher-suggestions, platform-voucher-discovery, save-and-apply. |
| [promotion/coupon_claims_deletion_strategy.md](./promotion/coupon_claims_deletion_strategy.md) | Hard deletion strategy cho coupon_claims. |

---

## 7. Service ↔ Service Contract Map (rút gọn từ CONTRACT_MATRIX)

Xem đầy đủ tại [Service Contract Matrix](./_contracts/CONTRACT_MATRIX.md#1-kafka-topic-map-ssot).

| Producer | Topic | Consumer |
|---|---|---|
| Order | `t.order.events` (10 events) | Promotion, Inventory, Cart, Notification, Analytics, Review |
| Order | `t.search.metrics.events` | Search |
| Payment | `t.payment.events` (9 events) | Order |
| Payment | `t.payment.webhooks.raw` (internal) | Payment |
| Inventory | `t.inventory.events` (4 events: STOCK_MUTATION, STOCK_CHANGED, RETURN_RECEIVED_ALL, SHIP_TO_CUSTOMER) | Product, Order, Analytics |
| Promotion | `t.promotion.markdown.events` (3 events) | Product |
| Promotion | `t.promotion.coupon.events` (5 events) | Notification, Analytics |
| Promotion | `t.promotion.budget.events` (1 event) | Notification, Frontend |

---

## 8. Sync API Catalog (rút gọn)

Xem đầy đủ tại [Service Contract Matrix §3](./_contracts/CONTRACT_MATRIX.md#3-sync-api-catalog-ssot).

| Endpoint | Direction |
|---|---|
| `POST /v1/checkout/preview` | Client → Checkout |
| `POST /v1/checkout/submit` | Client → Checkout |
| `POST /v1/orders/create-from-checkout` | Checkout → Order |
| `POST /v1/payments/initiate` | Order (Temporal) → Payment |
| `POST /v1/payments/{txn_id}/refund` | Order (Temporal) → Payment |
| `POST /v1/inventory/routing-options` | Checkout → Inventory |
| `POST /v1/inventory/ship-to-customer` | Admin → Inventory |
| `POST /v1/inventory/returns/refund-expectations` | Order → Inventory |
| `POST /v1/promotions/calculate-discount` | Checkout → Promotion |
| `POST /v1/promotions/{reserve,confirm,cancel,cancel-confirmed,refund}` | Order (Temporal) → Promotion |

---

## 9. Service ngoài scope (chưa đồng bộ trong đợt này)

| File | Note |
|---|---|
| [auth/](./auth/) | Auth Service — ngoài scope 14 phase hiện tại. |
| [cart/](./cart/) | Cart Service — ngoài scope. |
| [review/](./review/) | Review Service — ngoài scope. |
| [search/](./search/) | Search Service — ngoài scope. |
| [notification/](./notification/) | Notification Service — ngoài scope. |
| [analytics/](./analytics/) | Analytics Service — ngoài scope. |
| [common/](./common/) | Common layouts (Golang/Java) — ngoài scope. |
| [gateway/](./gateway/) | API Gateway — ngoài scope. |
| [product/README.md](./product/README.md) | Product Service — đã refactor theo `docs/_templates/service-documentation`. |

> Các service trên sẽ được sync ở giai đoạn tiếp theo. Hiện tại chỉ có 5 service (Checkout, Order, Payment, Inventory, Promotion) đạt 100% consistency sau Phase 14 (loại bỏ Shipping Service).
