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
| `CONTRACT_MATRIX.md` (đã xóa) | Matrix legacy; thay bằng [`contracts/catalog`](../contracts/catalog/) và machine-readable specs. |
| `IDEMPOTENCY_GUIDELINES.md` (đã xóa) | Hướng dẫn legacy; quy tắc hiện hành nằm trong docs service và contract tương ứng. |
| `OUTBOX_PATTERNS.md` (đã xóa) | Hướng dẫn legacy; contract/event docs hiện hành nêu boundary CDC/outbox. |
| `CHANGELOG.md` (đã xóa) | Lịch sử legacy không còn trong repository. |

### ADRs (Architecture Decision Records)

| File | Service | Decision |
|---|---|---|
| [order/decisions/ADR-009-customer-refund-does-not-mutate-promotion.md](./order/decisions/ADR-009-customer-refund-does-not-mutate-promotion.md) | Order + Promotion | Customer refund không mutate Promotion; không allocation theo SKU, không hoàn voucher/budget. |
| [order/decisions/ADR-010-selected-line-refund-policy.md](./order/decisions/ADR-010-selected-line-refund-policy.md) | Order + Inventory + Payment | Một refund case trọn đời/order; selected full-quantity lines; admin automatic chỉ trước dispatch. |
| [inventory/decisions/ADR-018-selected-line-refund-scope-and-pre-dispatch-release.md](./inventory/decisions/ADR-018-selected-line-refund-scope-and-pre-dispatch-release.md) | Inventory + Order | Lifetime selected scope và atomic all-line committed release trước dispatch. |
| [payment/decisions/ADR-011-one-lifetime-order-refund.md](./payment/decisions/ADR-011-one-lifetime-order-refund.md) | Payment + Order + Inventory | Một Payment refund transaction/order, Inventory evidence dạng Buyer/Admin. |
| [promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md](./promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md) | Promotion + Order | Durable command receipt; confirm/cancel-confirmed PostgreSQL-first; Redis finalize/repair; customer refund không mutate Promotion. |
| [promotion/decisions/ADR-014-claim-and-markdown-consistency.md](./promotion/decisions/ADR-014-claim-and-markdown-consistency.md) | Promotion + Product | Atomic claim boundary, immutable definition, causal markdown dedup và Product tombstone invalidation. |
| [product/decisions/ADR-016-state-discriminated-markdown-snapshot.md](./product/decisions/ADR-016-state-discriminated-markdown-snapshot.md) | Product + Promotion | ACTIVE full markdown; INACTIVE reason-only tombstone; nullable guarded projection. |

---

## 1. Checkout Service (BFF/Stateless)

> Thiết kế ở mức `target/proposed`; contracts Checkout, Cart, Product, User,
> Inventory, Promotion, Order, Gateway và Mesh đã được đối soát. Repository chưa
> có Checkout implementation nên adoption runtime vẫn là `not_started`.

| File | Mô tả |
|---|---|
| [checkout/README.md](./checkout/README.md) | Tổng quan, boundary, authoritative sources, API và bất biến. |
| [checkout/architecture.md](./checkout/architecture.md) | BFF stateless, fail-closed preview, atomic submit và recovery. |
| [checkout/domain.md](./checkout/domain.md) | Token-owned snapshot, state machine, rule và lỗi miền. |
| [checkout/interfaces.md](./checkout/interfaces.md) | Public API và mapping exact tới Cart, User, Product, Inventory, Promotion, Order. |
| [checkout/workflows.md](./checkout/workflows.md) | Preview, Submit, retry đồng thời, recovery và preview mới. |
| [checkout/data.md](./checkout/data.md) | Redis token-keyed state, TTL, CAS/Lua và no-secret/no-domain-cache policy. |
| [checkout/operations.md](./checkout/operations.md) | Deadline/retry, telemetry, failure mode, runbook và rollout. |
| [checkout/development.md](./checkout/development.md) | Checklist build/test khi implementation được thêm. |
| [checkout/decisions/README.md](./checkout/decisions/README.md) | Danh mục ADR hiện hành và lịch sử superseded. |

### Checkout contracts chuẩn

- `POST /api/v1/checkout/preview` — tạo immutable quotation.
- `POST /api/v1/checkout/submit` — submit theo token.
- `GET /api/v1/checkout/submissions/{checkout_token}` — recovery/status.
- Checkout gọi Cart snapshot, User address snapshot, Product batch, Inventory allocation preview, Promotion calculation và Order submission bằng contracts owner tương ứng.

---

## 2. Order Service (Orchestrator + Temporal Saga)

> Đã refactor theo `docs/_templates/service-documentation`; thiết kế ở mức
> `target/proposed`, đã có contract Saga liên service được liên kết nhưng chưa xác minh
> implementation/provider-consumer contract test.

| File | Mô tả |
|---|---|
| [order/README.md](./order/README.md) | Tổng quan, boundary, ownership, capability, dependency và bất biến. |
| [order/architecture.md](./order/architecture.md) | Temporal/Kafka/CDC, component, consistency, concurrency và topology. |
| [order/domain.md](./order/domain.md) | Aggregate, master/item/refund FSM, buyer tab, rule và lỗi miền. |
| [order/data.md](./order/data.md) | PostgreSQL logical schema, search indexes/view, transaction, Redis và lifecycle. |
| [order/interfaces.md](./order/interfaces.md) | HTTP/activity/event/Temporal semantics và liên kết các target contract Payment, Inventory, Delivery và Finance/Ops. |
| [order/workflows.md](./order/workflows.md) | Chín workflow gồm checkout, timeout, delivery, return, refund và late recovery. |
| [order/operations.md](./order/operations.md) | Worker, observability, failure mode, scaling, deploy và runbook. |
| [order/development.md](./order/development.md) | Checklist local/build/test/migration khi implementation được thêm. |
| [order/decisions/README.md](./order/decisions/README.md) | Danh mục ADR bảo toàn các quyết định của thiết kế cũ. |

---

## 3. Payment Service (PG State + Outbox)

> Đã refactor theo `docs/_templates/service-documentation`; thiết kế ở mức
> `target/proposed`, bảo toàn toàn bộ quyết định của tài liệu cũ. HTTP v1 và payment
> facts v2 đã có target contract nhưng chưa xác minh implementation/provider-consumer test.

| File | Mô tả |
|---|---|
| [payment/README.md](./payment/README.md) | Tổng quan, boundary, ownership, capability, dependency, bất biến và known gaps. |
| [payment/architecture.md](./payment/architecture.md) | Stripe SDK direct, webhook async qua Kafka, Outbox CDC, lock strategy, topology. |
| [payment/domain.md](./payment/domain.md) | Thuật ngữ, Payment FSM (T0–T9, gồm T3a) + Refund FSM (R1–R5), guard rules, invariant, lỗi miền. |
| [payment/data.md](./payment/data.md) | Schema logic 9 bảng, constraint, transaction, Redis key, outbox và retention. |
| [payment/interfaces.md](./payment/interfaces.md) | API initiate/cancel/webhook/query/refund và financial facts v2 `t.payment.events.v2`. |
| [payment/workflows.md](./payment/workflows.md) | Mười hai workflow: initiate, webhook, query/client-data, refund, expiration, reconciliation, poller, recovery, DLQ replay, review/correction, cancel và decline termination. |
| [payment/operations.md](./payment/operations.md) | SLO gợi ý, worker, observability, failure mode, runbook. |
| [payment/development.md](./payment/development.md) | Checklist build/test/migration khi implementation được thêm. |
| [payment/decisions/README.md](./payment/decisions/README.md) | Danh mục 15 ADR, gồm canonical AsyncAPI/structured CloudEvents, runtime, Stripe, webhook/outbox, concurrency, ordering, refund, retention, recovery và secret handling. |
| [Payment HTTP OpenAPI v1](../contracts/http/payment/v1/openapi.yaml) | Initiate/cancel/query/client-data/refund numbered payment attempt và Stripe webhook ingestion; target/proposed. |
| [Payment Events AsyncAPI v2](../contracts/events/payment/v2/asyncapi.yaml) | Mười financial facts, full structured CloudEvents, exact namespaced type và per-event JSON Schema; target/proposed. |
| [Payment Internal AsyncAPI v1](../contracts/events/payment/internal/v1/asyncapi.yaml) | Raw Stripe webhook buffer nội bộ, raw body base64 và DB-backed retry; target/proposed. |

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

## 5. Promotion Service (Redis Lua + PostgreSQL)

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

## 6. Delivery Service (target)

> Delivery là boundary target/proposed cho verified customer-delivery proof. Repository
> chưa có provider implementation; Inventory physical-dispatch không thay thế proof này.

| File | Mô tả |
|---|---|
| [delivery/README.md](./delivery/README.md) | Boundary, ownership và điều kiện phát verified proof cho từng order line. |
| [Delivery Events AsyncAPI v1](../contracts/events/delivery/v1/asyncapi.yaml) | `com.ecommerce.delivery.order-line.delivered.v1` cho Order; target/proposed. |

---

## 7. Product Detail BFF (target)

> Read composition layer cho PDP. Product giữ catalog/price; Inventory cấp advisory
> availability qua batch read; Review giữ review/rating. BFF không persist stock,
> đọc Review summary/preview theo request; Checkout preview allocation và Order reserve tại Inventory.

| File | Mô tả |
|---|---|
| [product-detail-bff/README.md](./product-detail-bff/README.md) | Boundary, ownership, dependency và rollout gate. |
| [product-detail-bff/architecture.md](./product-detail-bff/architecture.md) | Composition và consistency model. |
| [product-detail-bff/interfaces.md](./product-detail-bff/interfaces.md) | PDP API, Product buyer-read, Inventory batch-read và Review internal read. |
| [product-detail-bff/data.md](./product-detail-bff/data.md) | Không có persistent projection; cache/data boundary. |
| [product-detail-bff/decisions/](./product-detail-bff/decisions/README.md) | ADR Inventory-owned batch read và Review read composition không đổi ownership. |

---

## 8. Service ↔ Service Contract Map (rút gọn từ CONTRACT_MATRIX)

Nguồn đầy đủ hiện hành là [`contracts/catalog/interactions.yaml`](../contracts/catalog/interactions.yaml);
Service Contract Matrix legacy đã bị xóa.

| Producer | Topic | Consumer |
|---|---|---|
| Order | `t.order.events` (`com.ecommerce.order.paid.v1`, `com.ecommerce.order.completed.v1`) | Cart, Review |
| Payment | `t.payment.events.v2` (financial facts v2, full structured CloudEvents, không mang secret/provider payload) | Order, Analytics |
| Payment | `t.payment.webhooks.raw` (internal, raw body base64) | Payment webhook processor |
| Inventory | `t.inventory.order.events.v1` + `t.inventory.stock.events.v1` | Order consumes reservation/physical-dispatch/selected-scope inspection completion; Search consumes per-SKU availability |
| Delivery | `t.delivery.order.events.v1` (verified delivered proof từng line) | Order |
| Product | `t.product.events` / `product-deleted.v1` | Search; Promotion chỉ consume Product tombstone |
| Promotion | `t.promotion.markdown.events` (ACTIVE full payload hoặc INACTIVE tombstone); topic duy nhất Promotion phát, không có coupon/budget topic | Product |

---

## 9. Sync API Catalog (rút gọn)

Nguồn đầy đủ hiện hành là [`contracts/catalog/interactions.yaml`](../contracts/catalog/interactions.yaml);
Service Contract Matrix legacy đã bị xóa.

| Endpoint | Direction |
|---|---|
| `POST /api/v1/checkout/preview` | Client → Gateway → Checkout |
| `POST /api/v1/checkout/submit` | Client → Gateway → Checkout |
| `GET /api/v1/checkout/submissions/{checkout_token}` | Client → Gateway → Checkout |
| `GET /internal/v1/carts/{user_id}/checkout-snapshot` | Checkout → Cart |
| `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` | Checkout → User |
| Product gRPC `GetBatchSkus` | Checkout → Product |
| `POST /internal/inventory/v1/allocation-previews` | Checkout → Inventory |
| `POST /internal/v1/promotion-discount-calculations` | Checkout → Promotion |
| `POST /internal/v1/checkout-order-submissions` | Checkout → Order |
| `GET /internal/v1/checkout-order-submissions/{checkout_token}` | Checkout → Order recovery |
| `GET /api/v1/orders` / `GET /api/v1/orders/{order_id}` | Buyer → Order |
| `POST /api/v1/orders/{order_id}/{cancellation-requests,return-requests,payment-attempts}` | Buyer → Order |
| `POST /api/v1/admin/orders/{order_id}/{cancellation-requests,pre-dispatch-refund-requests}` | Admin → Order |
| `POST /v1/payments/initiate` | Order (Temporal) → Payment |
| `POST /v1/payments/{transactionId}/{cancel,refund}` | Order (Temporal) → Payment |
| `GET /v1/payments/{transactionId}`, `GET /v1/payments/{transactionId}/client-data`, `GET /v1/refunds/{refundTransactionId}` | Order (Temporal) → Payment |
| `POST /api/payments/webhook/stripe` | Stripe → Gateway → Payment `/v1/payments/webhook/stripe` |
| `POST /internal/inventory/v1/refund-scopes` | Order → Inventory; selected full-quantity lines, one scope/order lifetime |
| `POST /internal/inventory/v1/reservations/{order_id}/release-committed-before-dispatch` | Order → Inventory; automatic admin refund all-or-nothing release |
| `POST /api/v1/me/promotion-voucher-suggestions` | Client → Gateway → Promotion; chỉ wallet `SAVED` |
| `POST /api/v1/promotion-discovery` | Client → Gateway → Promotion; wallet + public `ACTIVE` |
| `PUT /api/v1/me/promotion-vouchers/{promotion_id}` | Customer → Promotion; save + evaluate explicit |
| `POST /api/v1/me/promotion-voucher-claims` | Customer → Promotion; limited claim |
| `POST /internal/v1/promotion-allocation/{reservations,confirmations,cancellations}` | Order (Temporal) → Promotion |
| `POST /internal/v1/promotion-allocation/technical-cancellations` | `promotion-legacy-migrator` → Promotion; dedicated proof/capability only |
| `POST /internal/v1/orders/{order_id}/financial-reconciliation-decisions` | `finance-ops`/`accounting-ops` → Order; evidence-backed decision only |

---

## 10. Service ngoài scope (chưa đồng bộ trong đợt này)

| File | Note |
|---|---|
| [auth/](./auth/) | Auth Service — ngoài scope 14 phase hiện tại. |
| [cart/README.md](./cart/README.md) | Cart Service — đã refactor theo `docs/_templates/service-documentation`. |
| [review/README.md](./review/README.md) | Review Service — đã refactor theo `docs/_templates/service-documentation`. |
| [search/README.md](./search/README.md) | Search Service — đã refactor theo `docs/_templates/service-documentation`. |
| [notification/README.md](./notification/README.md) | Notification Service — thiết kế `proposed`, runtime Go: lane ưu tiên, pipeline hai ranh giới, tracking SES, idempotency, ordering, backpressure, catalog template, Decide, data model, contract và interface (`contracts/events/notification/v1`, `contracts/http/notification`). |
| [analytics/](./analytics/) | Analytics Service — ngoài scope. |
| [common/](./common/) | Common layouts (Golang/Java) — ngoài scope. |
| [gateway/](./gateway/) | API Gateway (Traefik, north-south) — technology choice, design, runtime contract, implementation plan. |
| [product/README.md](./product/README.md) | Product Service — đã refactor theo `docs/_templates/service-documentation`. |

> Các service trên sẽ được sync ở giai đoạn tiếp theo. Năm service Checkout, Order,
> Payment, Inventory và Promotion được đồng bộ ở mức thiết kế; Delivery chỉ là boundary
> target/proposed, chưa có implementation (không phải Shipping Service legacy).

---

## 11. Platform — Service Mesh (`docs/mesh/`, target/proposed)

Istio ambient cho traffic east-west trên Kubernetes; Traefik giữ edge. Mesh là nơi thực thi
duy nhất cho mTLS và phân quyền service-to-service; `X-Service-Identity` do caller đặt, chỉ dùng
attribution. Chưa có manifest Kubernetes trong repository — mọi bảo đảm chỉ tồn tại trên cluster.

| File | Mô tả |
|---|---|
| [mesh/technology_choice.md](./mesh/technology_choice.md) | Vì sao Istio, Traefik giữ edge thay vì Istio Ingress Gateway, ambient thay vì sidecar, mesh là authority cho caller identity, phạm vi đợt 1. |
| [mesh/design.md](./mesh/design.md) | Namespace/ztunnel/waypoint, identity SPIFFE, ranh giới tin cậy `X-User-*`/`X-Service-Identity`, traffic policy, gRPC, Traefik trong mesh, chế độ lỗi. |
| [mesh/runtime-contract.md](./mesh/runtime-contract.md) | Quy ước bắt buộc: runtime, nhãn namespace, mTLS, ma trận `AuthorizationPolicy` dẫn xuất từ `contracts/catalog/interactions.yaml`, identity header, retry/timeout, egress, telemetry, tiêu chí xác minh, gaps. |
| [mesh/implementation_plan.md](./mesh/implementation_plan.md) | Giai đoạn 0–7 từ baseline Kubernetes tới vận hành, kiểm thử bắt buộc, migration/rollback. |
