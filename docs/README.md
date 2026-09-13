# E-Commerce Platform — Documentation Index

> **Contract foundation mới:** [`../contracts/`](../contracts/README.md) là nguồn
> chuẩn cho machine-readable contract mới (OpenAPI, Protobuf, AsyncAPI và JSON
> Schema). `docs/_contracts/CONTRACT_MATRIX.md` hiện là tài liệu legacy trong
> thời gian migration; không dùng nó làm template cho service mới.

Tài liệu trong `docs/` giải thích business, workflow và implementation. Khi dữ
liệu trên wire mâu thuẫn với một contract đã được đánh dấu `implemented` hoặc
`verified`, machine-readable spec trong `contracts/` được ưu tiên.

---

## 0. Legacy Contract Documents — `docs/_contracts/`

| File | Mô tả |
|---|---|
| [CONTRACT_MATRIX.md](./_contracts/CONTRACT_MATRIX.md) | Matrix legacy đang được migrate dần sang `contracts/catalog` và machine-readable specs. |
| [IDEMPOTENCY_GUIDELINES.md](./_contracts/IDEMPOTENCY_GUIDELINES.md) | Hướng dẫn triển khai legacy về Redis/DB/Temporal. |
| [OUTBOX_PATTERNS.md](./_contracts/OUTBOX_PATTERNS.md) | Hướng dẫn kiến trúc legacy về CDC, direct produce và polling. |
| [CHANGELOG.md](./_contracts/CHANGELOG.md) | Lịch sử mọi sync change theo phase. |

### ADRs (Architecture Decision Records)

| File | Service | Decision |
|---|---|---|
| [order/decisions/ADR-009-customer-refund-does-not-mutate-promotion.md](./order/decisions/ADR-009-customer-refund-does-not-mutate-promotion.md) | Order + Promotion | Customer refund không mutate Promotion; không allocation theo SKU, không hoàn voucher/budget. |
| [promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md](./promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md) | Promotion + Order | Durable command receipt; confirm/cancel-confirmed PostgreSQL-first; Redis finalize/repair; customer refund không mutate Promotion. |
| [promotion/decisions/ADR-014-claim-and-markdown-consistency.md](./promotion/decisions/ADR-014-claim-and-markdown-consistency.md) | Promotion + Product | Atomic claim boundary, immutable definition, causal markdown dedup và Product tombstone invalidation. |
| [product/decisions/ADR-016-state-discriminated-markdown-snapshot.md](./product/decisions/ADR-016-state-discriminated-markdown-snapshot.md) | Product + Promotion | ACTIVE full markdown; INACTIVE reason-only tombstone; nullable guarded projection. |

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

> Đã refactor theo cấu trúc chuẩn tại
> [`_templates/service-documentation`](./_templates/service-documentation/README.md).
> Bắt đầu từ [inventory/README.md](./inventory/README.md).

| File | Mô tả |
|---|---|
| [inventory/README.md](./inventory/README.md) | Điểm bắt đầu: trạng thái, boundary, data ownership, invariant, known gaps. |
| [inventory/architecture.md](./inventory/architecture.md) | Component, processing model, consistency và concurrency; vì sao Redis đứng trước PostgreSQL. |
| [inventory/domain.md](./inventory/domain.md) | Thuật ngữ, business rule, state machine và domain error. |
| [inventory/data.md](./inventory/data.md) | Ownership, schema, constraint, Redis key, outbox và lifecycle. |
| [inventory/interfaces.md](./inventory/interfaces.md) | HTTP, Temporal Activity và event semantics. |
| [inventory/workflows.md](./inventory/workflows.md) | 10 workflow end-to-end kèm failure path và idempotency. |
| [inventory/operations.md](./inventory/operations.md) | SLI, alert, failure mode, runbook và worker. |
| [inventory/development.md](./inventory/development.md) | Chạy, build, test và migration local. |
| [inventory/decisions/](./inventory/decisions/README.md) | 10 ADR: Redis commit source, stream outbox, CDC, suspension, snapshot v1... |

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
| [Promotion HTTP OpenAPI v1](../contracts/http/promotion/v1/openapi.yaml) | Machine-readable contract cho 24 operations; trạng thái `proposed`. |
| [Promotion Events AsyncAPI v1](../contracts/events/promotion/v1/asyncapi.yaml) | Một message phát (markdown snapshot cho Product) và một message nhận (`product-deleted.v1`); trạng thái `proposed`. |
| [promotion/workflows.md](./promotion/workflows.md) | Campaign schedule, claim, calculate, Saga technical compensation và recovery. |
| [promotion/data.md](./promotion/data.md) | PostgreSQL logical schema, Redis key/Lua, transaction và retention. |
| [promotion/operations.md](./promotion/operations.md) | SLI, failure mode, alert, runbook và workers/jobs. |
| [promotion/development.md](./promotion/development.md) | Prerequisite implementation, test matrix và Definition of Done. |
| [promotion/decisions/](./promotion/decisions/README.md) | 16 ADR; ADR-016 chốt phạm vi event v1 và receipt-driven finalizer. |

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
| Product | `t.product.events` / `product-deleted.v1` | Search; Promotion chỉ consume Product tombstone |
| Promotion | `t.promotion.markdown.events` (ACTIVE full payload hoặc INACTIVE tombstone); topic duy nhất Promotion phát, không có coupon/budget topic | Product |

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
| `POST /api/v1/me/promotion-voucher-suggestions` | Checkout/Gateway → Promotion; chỉ wallet `SAVED` |
| `POST /api/v1/promotion-discovery` | Checkout/Gateway → Promotion; wallet + public `ACTIVE` |
| `PUT /api/v1/me/promotion-vouchers/{promotion_id}` | Customer → Promotion; save + evaluate explicit |
| `POST /api/v1/me/promotion-voucher-claims` | Customer → Promotion; limited claim |
| `POST /internal/v1/promotion-discount-calculations` | Checkout → Promotion; pure query |
| `POST /internal/v1/promotion-allocation/{reservations,confirmations,cancellations,technical-cancellations}` | Order (Temporal) → Promotion |

---

## 9. Service ngoài scope (chưa đồng bộ trong đợt này)

| File | Note |
|---|---|
| [auth/](./auth/) | Auth Service — ngoài scope 14 phase hiện tại. |
| [cart/README.md](./cart/README.md) | Cart Service — đã refactor theo `docs/_templates/service-documentation`. |
| [review/README.md](./review/README.md) | Review Service — đã refactor theo `docs/_templates/service-documentation`. |
| [search/README.md](./search/README.md) | Search Service — đã refactor theo `docs/_templates/service-documentation`. |
| [notification/](./notification/) | Notification Service — ngoài scope. |
| [analytics/](./analytics/) | Analytics Service — ngoài scope. |
| [common/](./common/) | Common layouts (Golang/Java) — ngoài scope. |
| [gateway/](./gateway/) | API Gateway — ngoài scope. |
| [product/README.md](./product/README.md) | Product Service — đã refactor theo `docs/_templates/service-documentation`. |

> Các service trên sẽ được sync ở giai đoạn tiếp theo. Hiện tại chỉ có 5 service (Checkout, Order, Payment, Inventory, Promotion) đạt 100% consistency sau Phase 14 (loại bỏ Shipping Service).
