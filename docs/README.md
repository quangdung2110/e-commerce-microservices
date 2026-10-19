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
| [order/decisions/ADR-013-durable-refund-scope-finalization.md](./order/decisions/ADR-013-durable-refund-scope-finalization.md) | Order + Inventory | Durable close/cancel scope; approved no-payout incomplete return; financial result và finalization progress tách biệt. |
| [inventory/decisions/ADR-018-selected-line-refund-scope-and-pre-dispatch-release.md](./inventory/decisions/ADR-018-selected-line-refund-scope-and-pre-dispatch-release.md) | Inventory + Order | Lifetime selected scope và atomic all-line committed release trước dispatch. |
| [inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md](./inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md) | Inventory + Order | Reservation commands dùng durable receipt/query/replay; chỉ dispatch và inspection completion gửi qua Kafka; giữ atomic audit/outbox/availability work. |
| [payment/decisions/ADR-011-one-lifetime-order-refund.md](./payment/decisions/ADR-011-one-lifetime-order-refund.md) | Payment + Order + Inventory | Một Payment refund transaction/order, Inventory evidence dạng Buyer/Admin. |
| [promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md](./promotion/decisions/ADR-013-command-receipts-and-durable-finalization.md) | Promotion + Order | Durable command receipt; confirm/cancel-confirmed PostgreSQL-first; Redis finalize/repair; customer refund không mutate Promotion. |
| [promotion/decisions/ADR-014-claim-and-markdown-consistency.md](./promotion/decisions/ADR-014-claim-and-markdown-consistency.md) | Promotion + Product | Atomic claim boundary, immutable definition, causal markdown dedup và Product tombstone invalidation. |
| [product/decisions/ADR-016-state-discriminated-markdown-snapshot.md](./product/decisions/ADR-016-state-discriminated-markdown-snapshot.md) | Product + Promotion | ACTIVE full markdown; INACTIVE reason-only tombstone; nullable guarded projection. |
| [product/decisions/ADR-018-resolved-buyer-product-detail-projection.md](./product/decisions/ADR-018-resolved-buyer-product-detail-projection.md) | Product + Product Detail BFF | Buyer-read trả category path, nhãn thuộc tính, trục biến thể, media 20/10; không ETag. |
| [product-detail-bff/decisions/ADR-005-pdp-catalog-scope-and-media-url.md](./product-detail-bff/decisions/ADR-005-pdp-catalog-scope-and-media-url.md) | Product Detail BFF + Product | PDP trả category/thuộc tính/trục biến thể/gallery; BFF dựng URL CDN từ `storage_key`. |

---

## 1. Checkout Service (BFF/Stateless)

> Đã `implemented` và đồng bộ với code (2026-10-09): Java 21 + Spring Boot WebFlux
> (`java/services/checkout-service`, Checkout ADR-013); contracts Checkout, Cart, Product, User,
> Inventory, Promotion, Order, Gateway và Mesh đã được đối soát; adoption runtime là `pilot`.
> Chưa có kiểm thử end-to-end qua provider thật.

| File | Mô tả |
|---|---|
| [checkout/README.md](./checkout/README.md) | Tổng quan, boundary, thao tác ngoài BFF, dependency và bất biến. |
| [checkout/architecture.md](./checkout/architecture.md) | Wave gọi song song, deadline, hủy request, phân vai mesh/Gateway. |
| [checkout/domain.md](./checkout/domain.md) | Token-owned snapshot, state machine, luật `CHK-BR` và nhóm lỗi. |
| [checkout/interfaces.md](./checkout/interfaces.md) | Public API, nguồn từng field, error catalog, header và mapping tới Cart, User, Product, Inventory, Promotion, Order. |
| [checkout/workflows.md](./checkout/workflows.md) | Preview, khay voucher, Submit, recovery có giới hạn, tải lại trang. |
| [checkout/data.md](./checkout/data.md) | Redis token-keyed state, TTL, CAS/Lua, mã hoá và no-secret/no-domain-cache policy. |
| [checkout/operations.md](./checkout/operations.md) | SLO, ma trận lỗi, resilience, metric, alert, cấu hình, runbook và rollout. |
| [checkout/development.md](./checkout/development.md) | Stack Java, chạy local, test bắt buộc và Definition of Done. |
| [checkout/decisions/README.md](./checkout/decisions/README.md) | Danh mục ADR hiện hành và lịch sử superseded. |

### Checkout contracts chuẩn

- `POST /api/v1/checkout/preview` — tạo báo giá hiện hành của user.
- `GET /api/v1/checkout/previews/{checkout_token}/voucher-options` — khay voucher theo báo giá.
- `POST /api/v1/checkout/submit` — submit theo token.
- `GET /api/v1/checkout/submissions/{checkout_token}` — recovery/status.
- Checkout gọi Cart snapshot, User address snapshot, Product batch, Inventory allocation preview, Promotion calculation/voucher options và Order submission bằng contracts owner tương ứng.

---

## 2. Order Service (Orchestrator + Temporal Saga)

> Đã implement tại `java/services/order-service` (Java 21, Spring Boot 3.3, Temporal; Order
> ADR-020); contracts Order ở mức `implemented` với provider test, chưa có consumer contract test.

| File | Mô tả |
|---|---|
| [order/README.md](./order/README.md) | Tổng quan, boundary, ownership, capability, dependency và bất biến. |
| [order/architecture.md](./order/architecture.md) | Temporal/Kafka/CDC, component, consistency, concurrency và topology. |
| [order/domain.md](./order/domain.md) | Aggregate, master/item/refund FSM, buyer tab, rule và lỗi miền. |
| [order/data.md](./order/data.md) | PostgreSQL logical schema, search indexes/view, transaction, Redis và lifecycle. |
| [order/interfaces.md](./order/interfaces.md) | HTTP/activity/event/Temporal semantics và liên kết các contract Payment, Promotion, Inventory và Finance/Ops. |
| [order/workflows.md](./order/workflows.md) | Checkout, timeout, Admin xác nhận giao hàng, return/refund, durable scope finalization, late recovery và financial reconciliation. |
| [order/operations.md](./order/operations.md) | Worker, observability, failure mode, scaling, deploy và runbook. |
| [order/development.md](./order/development.md) | Build/test/chạy local, hiện trạng test tự động và migration. |
| [order/decisions/README.md](./order/decisions/README.md) | Danh mục ADR, gồm ADR-020 chốt runtime và các điểm làm rõ khi implement. |

---

## 3. Payment Service (PG State + Outbox)

> Runtime Spring Boot/Stripe/PostgreSQL đã hiện thực và được kiểm chứng offline.
> Audit 2026-10-11 bổ sung recovery/correction và đồng bộ contract;
> [runtime status](./payment/runtime-status.md) liệt kê KMS/metrics/heartbeat và gate môi trường còn thiếu.

| File | Mô tả |
|---|---|
| [payment/README.md](./payment/README.md) | Tổng quan, boundary, ownership, capability, dependency, bất biến và known gaps. |
| [payment/implementation-gates.md](./payment/implementation-gates.md) | Gate 1–3, điều kiện pass và bằng chứng executable trước từng giai đoạn implementation. |
| [payment/architecture.md](./payment/architecture.md) | Stripe SDK direct, webhook async qua Kafka, Outbox CDC, lock strategy, topology. |
| [payment/domain.md](./payment/domain.md) | Thuật ngữ, Payment FSM (T0–T9, gồm T3a) + Refund FSM (R1–R5), guard rules, invariant, lỗi miền. |
| [payment/data.md](./payment/data.md) | Schema logic 12 bảng, inbox lease, provider checkpoint, subledger, outbox và retention. |
| [payment/interfaces.md](./payment/interfaces.md) | API initiate/cancel/webhook/query/refund và financial facts v2 `t.payment.events.v2`. |
| [payment/workflows.md](./payment/workflows.md) | Mười bốn workflow: payment/refund, verified webhook/inbox recovery, provider recovery, review, dispute và subledger/balance reconciliation. |
| [payment/operations.md](./payment/operations.md) | SLO gợi ý, worker, observability, failure mode, runbook. |
| [payment/development.md](./payment/development.md) | Build/test/migration runtime hiện tại và gate môi trường. |
| [payment/decisions/README.md](./payment/decisions/README.md) | Danh mục 23 ADR, gồm verified inbox, lease/fencing, provider ordering, single-shot refund, Payment-owned subledger/dispute và retention/DR. |
| [Payment HTTP OpenAPI v1](../contracts/http/payment/v1/openapi.yaml) | Initiate/cancel/query/client-data/refund numbered payment attempt và Stripe webhook ingestion; implemented (offline). |
| [Payment internal OpenAPI v1](../contracts/http/payment/internal/v1/openapi.yaml) | Review/replay/reconciliation/freeze/ledger/dispute cho Finance/Accounting Ops; implemented (offline). |
| [Payment Events AsyncAPI v2](../contracts/events/payment/v2/asyncapi.yaml) | Mười lăm financial facts, full structured CloudEvents, exact namespaced type và per-event JSON Schema; implemented (offline). |
| [Payment Internal AsyncAPI v1](../contracts/events/payment/internal/v1/asyncapi.yaml) | Verified Stripe delivery nội bộ, exact bytes base64 và DB-backed retry; implemented (offline). |

---

## 4. Inventory Service (Relational authoritative state)

> Target design đã accepted cho Release 1 và đã implement tại `java/services/inventory-service`
> ([ADR-027](./inventory/decisions/ADR-027-pre-implementation-contract-corrections.md)); các gate production
> (MFA, RPO/RTO, load test, retention) còn mở theo [development.md](./inventory/development.md).
> Một relational primary là write authority; Redis/cache không quyết định stock.
> Reservation results dùng durable HTTP receipts/query/replay; Order chỉ nhận physical
> dispatch và selected-scope inspection completion qua Kafka.
> Bắt đầu từ [inventory/README.md](./inventory/README.md).

| File | Mô tả |
|---|---|
| [inventory/README.md](./inventory/README.md) | Điểm bắt đầu: trạng thái, boundary, data ownership, invariant, known gaps. |
| [inventory/architecture.md](./inventory/architecture.md) | Relational write authority, transaction/concurrency, projector và trust boundaries. |
| [inventory/domain.md](./inventory/domain.md) | Thuật ngữ, business rule, state machine và domain error. |
| [inventory/data.md](./inventory/data.md) | Authoritative state, command identity/receipt, movement/audit, required outbox và availability work. |
| [inventory/interfaces.md](./inventory/interfaces.md) | Workflow/read/admin/export API và ba public event types; Order nhận hai type. |
| [inventory/workflows.md](./inventory/workflows.md) | Reserve/payment/dispatch, timeout/recovery, selected refund scope và availability projection. |
| [inventory/operations.md](./inventory/operations.md) | SLI, alert, failure mode, runbook và worker. |
| [inventory/development.md](./inventory/development.md) | Trạng thái implementation, verification đã có và gates còn mở trước production. |
| [inventory/decisions/](./inventory/decisions/README.md) | Current accepted ADRs, publication scope ADR-020 và superseded historical decisions. |

---

## 5. Promotion Service (Redis Lua + PostgreSQL)

> Tài liệu theo cấu trúc chuẩn trong `docs/_templates`, `scope: current`: đã implement trong
> `java/services/promotion-service` và đã đối soát với source/migration/test (audit 2026-10-10,
> ADR-019). HTTP v1 và Events v1 `implemented`; chưa `verified` (chưa có consumer test với Promotion thật
> và E2E Promotion → Product; đoạn outbox → Kafka đã test với Debezium thật).

| File | Mô tả |
|---|---|
| [promotion/README.md](./promotion/README.md) | Tổng quan, boundary, ownership, capability, dependency và known gaps. |
| [promotion/architecture.md](./promotion/architecture.md) | Thành phần, processing, consistency, concurrency và deployment. |
| [promotion/domain.md](./promotion/domain.md) | Rule Engine, stacking, state machine, business rule và invariant. |
| [promotion/interfaces.md](./promotion/interfaces.md) | HTTP semantics đích, outbox event và Product markdown snapshot v1. |
| [Promotion HTTP OpenAPI v1](../contracts/http/promotion/v1/openapi.yaml) | Machine-readable contract cho 25 operations; trạng thái `implemented`. |
| [Promotion Events AsyncAPI v1](../contracts/events/promotion/v1/asyncapi.yaml) | Một message phát (markdown snapshot cho Product) và một message nhận (`product-deleted.v1`); trạng thái `implemented`. |
| [promotion/workflows.md](./promotion/workflows.md) | Campaign schedule, claim, calculate, reserve/confirm/cancel, legacy technical cleanup (deprecated) và recovery. |
| [promotion/data.md](./promotion/data.md) | PostgreSQL logical schema, Redis key/Lua, transaction và retention. |
| [promotion/operations.md](./promotion/operations.md) | SLI, failure mode, alert, runbook và workers/jobs. |
| [promotion/development.md](./promotion/development.md) | Prerequisite implementation, test matrix và Definition of Done. |
| [promotion/decisions/](./promotion/decisions/README.md) | 19 ADR (ADR-001..019); ADR-016 chốt phạm vi event v1 và receipt-driven finalizer, ADR-019 là các sửa sau audit. |

---

## 6. Delivery

> Không có Delivery service. Physical dispatch thuộc Inventory; delivery state thuộc Order và chỉ đổi
> bằng Admin delivery confirmation ([Order ADR-021](./order/decisions/ADR-021-admin-delivery-confirmation.md)).

---

## 7. Product Detail BFF

> Read composition layer cho PDP, đã implement (`java/services/product-detail-bff`, Java 21 +
> Spring WebFlux), `scope: current`. Product giữ catalog/price; Inventory cấp advisory
> availability qua batch read (gọi Inventory còn tắt bằng `PDP_INVENTORY_ENABLED=false` cho tới
> rollout gate); Review giữ review/rating. BFF không persist stock, đọc Review summary/preview
> theo request; Checkout preview allocation và Order reserve tại Inventory.

| File | Mô tả |
|---|---|
| [product-detail-bff/README.md](./product-detail-bff/README.md) | Boundary, ownership, dependency và rollout gate. |
| [product-detail-bff/architecture.md](./product-detail-bff/architecture.md) | Composition và consistency model. |
| [product-detail-bff/domain.md](./product-detail-bff/domain.md) | Ngôn ngữ chung và quy tắc hiển thị `PDP-BR-001..011`. |
| [product-detail-bff/interfaces.md](./product-detail-bff/interfaces.md) | PDP API, Product buyer-read, Inventory batch-read và Review internal read. |
| [product-detail-bff/workflows.md](./product-detail-bff/workflows.md) | `WF-PDP-001` đọc Product Detail và `WF-PDP-002` degrade/recovery. |
| [product-detail-bff/data.md](./product-detail-bff/data.md) | Không có persistent projection; cache/data boundary, không cache response degrade. |
| [product-detail-bff/operations.md](./product-detail-bff/operations.md) | SLO, resilience budget, phân chia với mesh/gateway, circuit breaker, cấu hình và runbook. |
| [product-detail-bff/development.md](./product-detail-bff/development.md) | Stack, chạy local, test bắt buộc và Definition of Done. |
| [product-detail-bff/decisions/](./product-detail-bff/decisions/README.md) | ADR-001..008: Inventory-owned batch read, Review read composition không đổi ownership, ranh giới Preview/Reserve, resilience budget, phạm vi catalog/URL media của PDP, runtime Java/WebFlux, làm rõ hot path và hiệu chỉnh sau audit (status ngoài contract, `storage_key`, readiness, request ID trong log). |

---

## 8. Service ↔ Service Contract Map (rút gọn từ CONTRACT_MATRIX)

Nguồn đầy đủ hiện hành là [`contracts/catalog/interactions.yaml`](../contracts/catalog/interactions.yaml);
Service Contract Matrix legacy đã bị xóa.

| Producer | Topic | Consumer |
|---|---|---|
| Order | `t.order.events` (`com.ecommerce.order.paid.v1`, `com.ecommerce.order.completed.v1`) | Cart, Review |
| Cart | `t.cart.events` (`com.ecommerce.cart.abandoned.v1`, event Cart duy nhất phát) | Notification |
| Review | `t.review.events` (tám type qua outbox + Debezium, key `product_id`: năm lifecycle fact `created`/`updated`/`hidden`/`approved`/`removed`, `metrics-updated`, `reply-notification-requested`, `removal-notification-requested`) | Review (aggregator tự consume năm lifecycle fact), Search (chỉ `metrics-updated`), Notification (chỉ hai notification request) |
| Auth | `t.auth.events` (`com.ecommerce.auth.user-deleted.v1` cho mọi PII owner; `user-verified-success.v1` chỉ cho User) | User, Notification, Review, Chat, Cart |
| User | `t.user.events` (`com.ecommerce.user.profile-updated.v1`, `com.ecommerce.user.preferences-updated.v1`; không republish `USER_DELETED`) | Chat (profile), Notification (preferences) |
| Notification | `t.notification.outcomes.v1` (`delivered`, `bounced`, `failed`, `complaint-received`) | Analytics; User chỉ consume `complaint-received` để tắt `marketing_enabled` ở nguồn |
| Payment | `t.payment.events.v2` (financial facts v2, full structured CloudEvents, không mang secret/provider payload) | Order, Analytics |
| Payment | `t.payment.webhooks.raw` (internal, chỉ delivery đã verify; exact body base64) | Payment webhook processor |
| Inventory | `t.inventory.order.events.v1` + `t.inventory.stock.events.v1` | Order consumes only physical dispatch/selected-scope inspection completion; reservation commands use HTTP receipts/query/replay; Search consumes per-SKU availability |
| Product | `t.product.events` / `product-deleted.v1` | Search; Promotion chỉ consume Product tombstone |
| Promotion | `t.promotion.markdown.events` (ACTIVE full payload hoặc INACTIVE tombstone); topic duy nhất Promotion phát, không có coupon/budget topic | Product |

---

## 9. Sync API Catalog (rút gọn)

Nguồn đầy đủ hiện hành là [`contracts/catalog/interactions.yaml`](../contracts/catalog/interactions.yaml);
Service Contract Matrix legacy đã bị xóa.

| Endpoint | Direction |
|---|---|
| `POST /api/v1/checkout/preview` | Client → Gateway → Checkout |
| `GET /api/v1/checkout/previews/{checkout_token}/voucher-options` | Client → Gateway → Checkout |
| `POST /api/v1/checkout/submit` | Client → Gateway → Checkout |
| `GET /api/v1/checkout/submissions/{checkout_token}` | Client → Gateway → Checkout |
| `GET /internal/v1/carts/{user_id}/checkout-snapshot` | Checkout → Cart |
| `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` | Checkout → User |
| Product gRPC `GetBatchSkus` | Checkout → Product |
| `POST /internal/inventory/v1/allocation-previews` | Checkout → Inventory |
| `POST /internal/v1/promotion-discount-calculations` | Checkout → Promotion |
| `POST /internal/v1/promotion-voucher-options` | Checkout → Promotion; khay voucher |
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
| `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/{close,close-incomplete,cancel}` | Order → Inventory; guarded durable finalization; no-payout Ops decision for incomplete/no-receipt return |
| `POST /internal/inventory/v1/reservations/{order_id}/release-committed-before-dispatch` | Order → Inventory; automatic admin refund all-or-nothing release |
| `POST /api/v1/me/promotion-voucher-suggestions` | Client → Gateway → Promotion; chỉ wallet `SAVED`; trang checkout không dùng |
| `POST /api/v1/promotion-discovery` | Client → Gateway → Promotion; wallet + public `ACTIVE`; trang checkout không dùng |
| `PUT /api/v1/me/promotion-vouchers/{promotion_id}` | Customer → Promotion; save explicit, evaluate khi có `context` |
| `POST /api/v1/me/promotion-voucher-claims` | Customer → Promotion; limited claim |
| `POST /internal/v1/promotion-allocation/{reservations,confirmations,cancellations}` | Order (Temporal) → Promotion |
| `POST /internal/v1/promotion-allocation/technical-cancellations` | `promotion-legacy-migrator` → Promotion; dedicated proof/capability only |
| `POST /internal/v1/orders/{order_id}/financial-reconciliation-decisions` | `finance-ops`/`accounting-ops` → Order; evidence-backed decision only |

---

## 10. Service ngoài scope (chưa đồng bộ trong đợt này)

| File | Note |
|---|---|
| [auth/README.md](./auth/README.md) | Auth Service — Identity Provider đã implement (`go/services/auth-service`), `scope: current`: account, session, JWKS, listener internal cho Notification; ADR-001..009 (gồm thứ tự publish/marker, mesh cho listener internal, compare-and-set account, đăng nhập kiểm mật khẩu trước trạng thái). Contract còn `proposed` trong `contracts/ADOPTION.md`. |
| [cart/README.md](./cart/README.md) | Cart Service — đã refactor theo `docs/_templates/service-documentation`. |
| [review/README.md](./review/README.md) | Review Service — entitlement, review, media, moderation, legal takedown, privacy erasure và rating aggregate; đã đồng bộ với `java/services/review-service` theo `docs/_templates/service-documentation` (ADR-001..013); HTTP v1, internal v1 và Events v1 `implemented`, `HttpContractIT` kiểm route và response với OpenAPI. |
| [search/README.md](./search/README.md) | Search Service — Elasticsearch projection cho storefront search/browse, đã đồng bộ với `java/services/search-service` theo `docs/_templates/service-documentation` (ADR-001..022: no-DLQ, reindex ba pha có resume/cancel/rollback/verify, tombstone window 45 ngày, hiệu chỉnh sau audit 2026-10-10); HTTP v2 `implemented`, `HttpErrorMappingIT` và `ReindexLifecycleIT` kiểm lỗi giao thức và vòng đời reindex trên Elasticsearch thật. |
| [notification/README.md](./notification/README.md) | Notification Service — đã implement phase 1 (`go/services/notification-service`, `implemented`; contract còn `proposed` trong `contracts/ADOPTION.md`), chưa load test; D-01…D-24: lane ưu tiên, pipeline hai ranh giới, tracking SES, idempotency, ordering, backpressure, catalog template, Decide, data model, contract và interface (`contracts/events/notification/v1`, `contracts/http/notification`). |
| [analytics/](./analytics/) | Analytics Service — ngoài scope. |
| [common/](./common/) | Common layouts (Golang/Java) — ngoài scope. |
| [gateway/](./gateway/) | API Gateway (Traefik, north-south) — technology choice, design, runtime contract, implementation plan. |
| [product/README.md](./product/README.md) | Product Service — đã refactor theo `docs/_templates/service-documentation`. |
| [user/README.md](./user/README.md) | User Service — profile, address book, preferences; đã đồng bộ với `go/services/user-service` theo `docs/_templates/service-documentation` (ADR-001..012). |

> Các service trên sẽ được sync ở giai đoạn tiếp theo. Các service Checkout, Order,
> Payment và Inventory được đồng bộ ở mức thiết kế (Promotion đã đồng bộ với code, xem §5). Không có Delivery
> service: Admin xác nhận giao hàng qua Order ([Order ADR-021](./order/decisions/ADR-021-admin-delivery-confirmation.md)).

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
| [mesh/decisions/](./mesh/decisions/README.md) | ADR-001: manifest Istio sinh từ runtime contract (`deploy/mesh/`), Istio 1.24.2, dịch đường dẫn, kiểm tra không cần cluster. |

---

## 11b. Hiệu năng và độ bền (`docs/performance/`)

| Tài liệu | Nội dung |
|---|---|
| [performance/load-test.md](./performance/load-test.md) | Load test k6 chỉ đọc cho route storefront (qua gateway và thẳng service), kết quả đo, giới hạn của số đo, và fault injection ở mức socket cho Order và Search ([contracts ADR-010](../contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md)). |
| [performance/inventory-reserve-hot-sku-baseline-2026-10-19.md](./performance/inventory-reserve-hot-sku-baseline-2026-10-19.md) | Baseline (trước khi sửa) cho ReserveOrder của Inventory trên một SKU nóng, stack cô lập `scripts/ci/inventory-bench.sh`: đạt SLO tới 250/s, trần ≈ 330/s do hàng khoá `stock_position` (CPU còn dư một nửa); 2 000 SKU nguội đạt 600/s; trộn 50/50 làm đơn nguội chậm theo; 0 `INVENTORY_BUSY` nên alert busy không bắt được (đã thêm alert latency/pool, [Inventory ADR-032](./inventory/decisions/ADR-032-reserve-saturation-alerts.md)); mức sàn COMMIT một hàng ≈ 700–750/s; đối chiếu envelope 10 Reserve/s của Release 1. |
| [performance/inventory-flash-sale-baseline-2026-10-19.md](./performance/inventory-flash-sale-baseline-2026-10-19.md) | Baseline flash sale (Reserve + Commit/Release trên một SKU nóng, chưa sửa code): với 64 request đồng thời như bulkhead của Order đạt SLO tới 100 đơn/s, trần ≈ 150–167 đơn/s, không sụp; không giới hạn đồng thời thì congestion collapse; đính chính trần chỉ Reserve ≈ 285–290/s; đối soát DB khớp ở mọi bậc. |

## 12. Frontend (`docs/frontend/`)

Ứng dụng Next.js trong `frontend/` gồm storefront và trang quản trị `/admin`.

| File | Mô tả |
|---|---|
| [frontend/README.md](./frontend/README.md) | Điểm bắt đầu của tài liệu frontend. |
| [frontend/tech-stack.md](./frontend/tech-stack.md) | Công nghệ đã chọn và lý do. |
| [frontend/sitemap.md](./frontend/sitemap.md) | Route storefront, tài khoản, admin; API nguồn, quyền, hiện trạng, khoảng trống API. |
| [frontend/implementation-plan.md](./frontend/implementation-plan.md) | Lộ trình 14 bước; chi tiết bước 0 (nền tảng) và bước 1 (khung admin). |
| [frontend/decisions/](./frontend/decisions/README.md) | ADR-001 admin chung ứng dụng; ADR-002 ranh giới render và xác thực. |
