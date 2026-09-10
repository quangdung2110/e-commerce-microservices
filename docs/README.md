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
| [order/decisions/ADR-009-customer-refund-does-not-mutate-promotion.md](./order/decisions/ADR-009-customer-refund-does-not-mutate-promotion.md) | Order + Promotion | Customer refund không mutate Promotion; không allocation theo SKU, không hoàn voucher/budget. |
| [promotion/decisions/ADR-010-campaign-lifecycle-and-scheduling.md](./promotion/decisions/ADR-010-campaign-lifecycle-and-scheduling.md) | Promotion | Campaign lifecycle dẫn xuất; Redis ZSET có revision và DB fallback. |
| [promotion/decisions/ADR-011-transactional-outbox-cdc.md](./promotion/decisions/ADR-011-transactional-outbox-cdc.md) | Promotion + Product | Transactional outbox/CDC và full markdown snapshot. |
| [promotion/decisions/ADR-012-wallet-redemption-and-no-refund.md](./promotion/decisions/ADR-012-wallet-redemption-and-no-refund.md) | Promotion | Wallet là redemption state; schema bảy bảng; customer refund không hoàn voucher. |

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

> Đã refactor theo `docs/_templates/service-documentation`; thiết kế ở mức
> `target/proposed`, chưa xác minh implementation và chưa đối soát contract liên service.

| File | Mô tả |
|---|---|
| [order/README.md](./order/README.md) | Tổng quan, boundary, ownership, capability, dependency và bất biến. |
| [order/architecture.md](./order/architecture.md) | Temporal/Kafka/CDC, component, consistency, concurrency và topology. |
| [order/domain.md](./order/domain.md) | Aggregate, master/item/refund FSM, buyer tab, rule và lỗi miền. |
| [order/data.md](./order/data.md) | PostgreSQL logical schema, search indexes/view, transaction, Redis và lifecycle. |
| [order/interfaces.md](./order/interfaces.md) | HTTP/activity/event/Temporal semantics; contract chưa được chuẩn hóa ở phase này. |
| [order/workflows.md](./order/workflows.md) | Chín workflow gồm checkout, timeout, delivery, return, refund và late recovery. |
| [order/operations.md](./order/operations.md) | Worker, observability, failure mode, scaling, deploy và runbook. |
| [order/development.md](./order/development.md) | Checklist local/build/test/migration khi implementation được thêm. |
| [order/decisions/README.md](./order/decisions/README.md) | Danh mục ADR bảo toàn các quyết định của thiết kế cũ. |

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

## 4. Promotion Service (Redis Lua + PostgreSQL)

> Tài liệu đã refactor theo cấu trúc chuẩn trong `docs/_templates`; thiết kế đang ở
> trạng thái `proposed` và chưa có implementation để verification.

| File | Mô tả |
|---|---|
| [promotion/README.md](./promotion/README.md) | Tổng quan, boundary, ownership, capability, dependency và known gaps. |
| [promotion/architecture.md](./promotion/architecture.md) | Thành phần, processing, consistency, concurrency và deployment. |
| [promotion/domain.md](./promotion/domain.md) | Rule Engine, stacking, state machine, business rule và invariant. |
| [promotion/interfaces.md](./promotion/interfaces.md) | HTTP semantics đích, outbox event và Product markdown snapshot v1. |
| [promotion/workflows.md](./promotion/workflows.md) | Campaign schedule, claim, calculate, Saga technical compensation và recovery. |
| [promotion/data.md](./promotion/data.md) | PostgreSQL logical schema, Redis key/Lua, transaction và retention. |
| [promotion/operations.md](./promotion/operations.md) | SLI, failure mode, alert, runbook và workers/jobs. |
| [promotion/development.md](./promotion/development.md) | Prerequisite implementation, test matrix và Definition of Done. |
| [promotion/decisions/](./promotion/decisions/README.md) | 12 ADR; ADR-010..012 là quyết định hiện hành của lần refactor. |

---

## 6. Product Detail BFF (target)

> Read composition layer cho PDP. Product giữ catalog/price; Inventory cấp advisory
> availability qua batch read; Review giữ review/rating. BFF không persist stock,
> đọc Review summary/preview theo request và Checkout vẫn reserve tại Inventory.

| File | Mô tả |
|---|---|
| [product-detail-bff/README.md](./product-detail-bff/README.md) | Boundary, ownership, dependency và rollout gate. |
| [product-detail-bff/architecture.md](./product-detail-bff/architecture.md) | Composition và consistency model. |
| [product-detail-bff/interfaces.md](./product-detail-bff/interfaces.md) | PDP API, Product buyer-read, Inventory batch-read và Review internal read. |
| [product-detail-bff/data.md](./product-detail-bff/data.md) | Không có persistent projection; cache/data boundary. |
| [product-detail-bff/decisions/](./product-detail-bff/decisions/README.md) | ADR Inventory-owned batch read và Review read composition không đổi ownership. |

---

## 7. Service ↔ Service Contract Map (rút gọn từ CONTRACT_MATRIX)

Xem đầy đủ tại [Service Contract Matrix](./_contracts/CONTRACT_MATRIX.md#1-kafka-topic-map-ssot).

| Producer | Topic | Consumer |
|---|---|---|
| Order | `t.order.events` (8 event types theo thiết kế hiện tại) | Inventory, Cart, Notification, Analytics, Review; Promotion không consume refund |
| Payment | `t.payment.events` (9 events) | Order |
| Payment | `t.payment.webhooks.raw` (internal) | Payment |
| Inventory | `t.inventory.events` (4 events: STOCK_MUTATION, STOCK_CHANGED, RETURN_RECEIVED_ALL, SHIP_TO_CUSTOMER) | Product, Order, Analytics |
| Promotion | `t.promotion.markdown.events` (một full-snapshot type, state ACTIVE/INACTIVE) | Product |
| Promotion | `t.promotion.coupon.events` | Notification, Analytics; không có coupon-refunded event |
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
| `POST /v1/promotions/{reserve,confirm,cancel,cancel-confirmed}` | Order (Temporal) → Promotion |

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
