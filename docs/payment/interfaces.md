---
doc_id: payment-interfaces
service: payment
scope: target
status: implemented
owners:
  - Payment Service team
evidence:
  - contracts/http/payment/v1/openapi.yaml
  - contracts/http/payment/internal/v1/openapi.yaml
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/catalog/interactions.yaml
  - java/services/payment-service/src/main/java/com/platform/paymentservice/adapter/in
  - java/services/payment-service/src/test/java/com/platform/paymentservice
verified_against_commit: null
---

# Payment Service — Giao diện tích hợp

> **Đối chiếu runtime 2026-10-11:** xem [phạm vi hiện tại và năng lực chưa triển khai](./runtime-status.md)
> và [ADR-023](./decisions/ADR-023-reconciliation-ownership-and-audit-corrections.md). Các mục tiêu KMS, metric nghiệp vụ,
> heartbeat, health/DR/export và RBAC bên ngoài không phải bằng chứng đã được runtime hiện thực.

> Contract chuẩn trên wire là [Payment HTTP v1](../../contracts/http/payment/v1/openapi.yaml)
> và [Payment events v2](../../contracts/events/payment/v2/asyncapi.yaml). Topic
> webhook nội bộ có contract riêng tại
> [Payment internal webhook v1](../../contracts/events/payment/internal/v1/asyncapi.yaml).
> Bề mặt vận hành có contract riêng tại
> [Payment internal HTTP v1](../../contracts/http/payment/internal/v1/openapi.yaml).
> File này
> giải thích **semantics** (idempotency, lỗi, nghĩa vụ consumer, ví dụ); khi lệch,
> contract file thắng và file này phải sửa theo. Ví dụ JSON minh họa, không phải
> fixture test.

## 1. Danh mục tương tác

| Interaction ID (catalog) | Hướng | Giao thức | Provider → Consumer | Auth / transport | Contract |
|---|---|---|---|---|---|
| `order-initiates-payment` | Inbound | HTTP/JSON | Payment ← Order (Temporal Activities) | Mesh mTLS, principal `ecommerce/order-service`; timeout 15s, mesh **không retry** | OpenAPI v1: initiate, cancel, refund, `GET` payment/client-data/refund |
| `stripe-delivers-payment-webhook` | Inbound | HTTPS | Payment ← Gateway ← Stripe | Gateway giữ exact bytes; ingress Payment verify signature/300s trước inbox/Kafka | OpenAPI v1 ingress + Stripe event schema (API version pin) |
| `payment-publishes-payment-events` | Outbound | Kafka `t.payment.events.v2` | Payment → Order, Analytics | Outbox CDC, at-least-once, key `order_id` | AsyncAPI v2 |
| `t.payment.webhooks.raw` | Internal | Kafka | `payment-api` → `payment-worker` | Nội bộ, confidential, retention 7 ngày | Internal AsyncAPI v1 |
| Payment operations | Inbound | Internal HTTP | Payment ← Finance Ops / Accounting Ops | Mesh mTLS + RBAC + `X-Service-Identity` theo `payment.identity.operations-callers` (`finance-ops`, `accounting-ops`; thiếu `401`, ngoài danh sách `403`, [ADR-028](decisions/ADR-028-finance-ops-caller-allowlist.md)) + actor; mutation maker-checker + idempotency | Internal OpenAPI v1 |
| Stripe API | Outbound | HTTPS (`stripe-java`) | Stripe ← Payment | Secret key, egress `ServiceEntry`, idempotency key per operation | Stripe API (pin version) |

Payment **không** có route cho buyer/CSKH; mọi read đi qua Order. Payment không
consume topic nào của service khác ([ADR-010](./decisions/ADR-010-refund-only-by-explicit-order-command.md)).

Public/service OpenAPI v1 bao phủ đúng 7 operation: 6 operation nội bộ cho Order và
một webhook cho Stripe. Actuator/health không thuộc contract. Internal OpenAPI có
đúng 9 operation: list/claim/resolve review case, apply FSM correction, replay
verified webhook, start reconciliation, set freeze, read immutable journal và read
dispute. Mọi mutation dùng idempotency; resolve/correction/replay/reconciliation/
freeze dùng maker-checker. Header attribution không thay cho mesh principal/RBAC.

Manual correction FAILED bắt buộc `error_code`, CANCELED bắt buộc `reason_code`;
terminal non-capture đòi evidence object `{id: stored PaymentIntent, status: canceled}`
và `provider_status=canceled`. Evidence tham gia fingerprint. R1 refund tăng payment
financial version dù status giữ nguyên. Manual INITIATING→PENDING chưa hỗ trợ.
Start reconciliation chạy đồng bộ, thành công trả 202 với outcome COMPLETED; lỗi
đã lưu theo key cần key mới nếu Ops muốn chạy lại. Freeze chặn ghi ledger tự động
của balance lane; run đó FAILED và không advance watermark.

## 2. HTTP API v1

### 2.1. Quy ước chung

| Khía cạnh | Quy tắc |
|---|---|
| Base path | `/v1` nội bộ mesh. Riêng Stripe gọi public `/api/payments/webhook/stripe`; Gateway rewrite path sang `/v1/payments/webhook/stripe` và giữ nguyên raw body/header |
| Xác thực | Mesh mTLS + `AuthorizationPolicy`; Order bắt buộc gửi `X-Service-Identity: order-service`. Header không phải bằng chứng authentication, nhưng Payment kiểm nó với `payment.identity.allowed-callers` (mặc định `order-service`) làm defense-in-depth: thiếu là `401 UNAUTHENTICATED`, ngoài danh sách là `403 FORBIDDEN` ([contracts ADR-004](../../contracts/decisions/ADR-004-service-identity-allowlist.md), [ADR-026](decisions/ADR-026-service-identity-allowlist-and-401-403.md)). Webhook Stripe không áp dụng; route Finance Ops có allowlist riêng `payment.identity.operations-callers` ([ADR-028](decisions/ADR-028-finance-ops-caller-allowlist.md)), nên `order-service` gọi `/internal/v1/payment/**` nhận `403` |
| Correlation | `X-Correlation-Id` 1–128 ký tự (khớp financial event schema), bắt buộc trên 6 operation Order-facing (Order truyền Temporal Workflow ID); Payment echo lại và ghi vào `outbox_events.headers` |
| Idempotency | Header `Idempotency-Key` (16–255 ký tự) bắt buộc với ba command Order-facing `initiate/cancel/refund`; không áp dụng cho webhook; namespace là `(action, key)` và mỗi cặp chỉ có một fingerprint; xem §2.2 |
| Money | `{ "amount_minor": <int64>, "currency": "VND" }`; VND zero-decimal, không nhân/chia |
| ID | Payment sinh `TXN_<ULID>`, `RFD_<ULID>` (tối đa 64 ký tự trong DB); `refund_request_id`/evidence reference opaque ≤ 128; Order gửi `order_id` là chuỗi số dương vừa PostgreSQL `BIGINT` |
| Thời gian | RFC 3339 UTC |
| Lỗi | `application/problem+json` `{ code, retryable, correlation_id }`; `retryable` là authority cho caller |
| Cache | Mọi response Order-facing dùng `Cache-Control: private, no-store`; webhook dùng `Cache-Control: no-store` |
| Timeout | Caller đặt ≤ 15s (mesh); Payment tự cắt Stripe ở 15s read timeout để không vượt |

### 2.2. Idempotency của command

Payment lưu `payment_idempotency (idempotency_key, action, request_fingerprint,
status)` trước khi xử lý (`data.md §3.7`). `action` bao phủ
`INITIATE/CANCEL/REFUND` và năm internal operation
`REVIEW_CLAIM/REVIEW_RESOLVE/WEBHOOK_REPLAY/RECONCILE/FREEZE`:

| Tình huống | Kết quả |
|---|---|
| `(action, key)` mới | Xử lý; lưu `COMPLETED` + response |
| Cùng `(action, key)`, cùng fingerprint, `COMPLETED` | Trả lại response đã lưu (initiate: client data được giải mã lại nếu còn dùng được) |
| Cùng `(action, key)`, request khác còn giữ execution lock | `409 REQUEST_IN_PROGRESS`, `retryable=true` (Order retry sau vài giây) |
| Lease hết hạn | Worker mới atomic takeover, tăng `lease_token`; worker cũ không thể complete |
| Cùng `(action, key)`, fingerprint khác | `422 IDEMPOTENCY_KEY_REUSED`, `retryable=false` |
| Request đang gọi provider nhận outcome chưa rõ | Request đó nhận `503 PROVIDER_OUTCOME_UNKNOWN`, `retryable=true`; bản ghi vẫn `IN_PROGRESS`; Order **phải** retry cùng key, không đổi attempt |
| Replay initiate khi không còn request giữ execution lock nhưng recovery đang xử lý provider outcome | `200 status=INITIATING`, không có client data; Order tiếp tục poll/retry cùng key |

Fingerprint = SHA-256 của command envelope canonical `{action, path_parameters,
body}` (sort key, bỏ whitespace). Vì vậy `transactionId` bắt buộc nằm trong
fingerprint của cancel/refund; cùng key + cùng body nhưng đổi transaction không thể
replay nhầm resource. Namespace lưu trữ là `(action, key)`; caller vẫn nên sinh key
riêng cho từng business command để trace rõ ràng. Redis chỉ là fast-path; mất Redis
không đổi kết quả.

### 2.3. Bảng lỗi

| `code` | HTTP | `retryable` | Khi nào |
|---|---|---|---|
| `IDEMPOTENCY_KEY_MISSING` | 400 | false | Thiếu header trên `POST` |
| `VALIDATION_FAILED` | 400 | false | Schema/enum sai, amount ≤ 0 |
| `PAYMENT_NOT_SUCCESS` | 400 | false | Refund trên payment không `SUCCESS` |
| `REFUND_AMOUNT_EXCEEDED` | 400 | false | `amount > amount_minor` |
| `ORDER_MISMATCH` | 400 | false | `order_id` không khớp transaction |
| `PAYMENT_NOT_FOUND` / `REFUND_NOT_FOUND` | 404 | false | ID không tồn tại |
| `REQUEST_IN_PROGRESS` | 409 | true | Cùng `(action, key)` và request khác còn giữ execution lock |
| `ORDER_REFUND_ALREADY_EXISTS` | 409 | false | Order đã có refund với key khác |
| `ATTEMPT_ALREADY_EXISTS` | 409 | false | `(order_id, payment_attempt_number)` đã có với key khác |
| `IDEMPOTENCY_KEY_REUSED` | 422 | false | Cùng `(action, key)` nhưng khác command envelope |
| `PROVIDER_OUTCOME_UNKNOWN` | 503 | true | Stripe timeout/ambiguous sau khi đã commit operation |
| `PROVIDER_REQUEST_REJECTED` | 503 | false | Stripe xác định từ chối `PaymentIntent.create`; operation terminal và Payment mở review, retry không được gọi provider lại |
| `PROVIDER_UNAVAILABLE` | 503 | true | Stripe lỗi trước khi gọi (5xx, network) |
| `DEPENDENCY_UNAVAILABLE` | 503 | true | PostgreSQL/KMS không sẵn sàng |

`503` có `retryable=true` kèm `Retry-After: 2` (giây); `PROVIDER_REQUEST_REJECTED` (`retryable=false`) không có header
([contracts ADR-005](../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md)). Webhook Stripe `503`
cũng kèm `Retry-After: 2`, chỉ mang tính thông tin vì Stripe tự lên lịch retry. Order đọc header qua `HttpRemote` (ghi log)
và không đổi hành vi retry: retry vẫn do saga sở hữu.

`PAYMENT_NOT_CANCELABLE` không là kết luận theo local status: Payment vẫn gọi
provider; chỉ actual response chưa cancel được/không rõ mới trả `CANCEL_PENDING`.

### 2.4. `POST /v1/payments/initiate`

**Mục đích:** tạo hoặc replay một numbered payment attempt; trả client data để Order
relay cho frontend.

```http
POST /v1/payments/initiate
Idempotency-Key: ord-10001-attempt-1-7f3a…
X-Correlation-Id: order-checkout-8c1d…
```
```json
{ "order_id": "10001", "payment_attempt_number": 1,
  "amount": { "amount_minor": 995000, "currency": "VND" },
  "user_id": "6f4c2b1a-…", "return_url": null }
```

Response `200` khi đã bind PaymentIntent:

```json
{ "transaction_id": "TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "payment_attempt_number": 1,
  "status": "PENDING", "client_secret": "pi_3Q…_secret_…", "publishable_key": "pk_test_…",
  "expires_at": "2026-02-28T10:15:00Z" }
```

Response `200` khi provider chưa bind, request gọi provider trước đó đã kết thúc và
durable recovery đã nhận operation (không còn request giữ execution lock):

```json
{ "transaction_id": "TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "payment_attempt_number": 1, "status": "INITIATING" }
```

Semantics:

- Một `Idempotency-Key` ↔ một `payment_attempt_number`. Attempt mới chỉ hợp lệ sau
  khi attempt trước có fact terminal non-capture (`PAYMENT_FAILED/EXPIRED/CANCELED`),
  và phải là số kế tiếp; nhảy số/tái dùng → `409 ATTEMPT_ALREADY_EXISTS` hoặc `400`.
- Payment commit `INITIATING` + `ProviderOperation CREATE` trước khi gọi Stripe;
  `PaymentIntent.create` với `amount_minor` nguyên, `currency=vnd`, metadata
  `payment_transaction_id`, `order_id`, `payment_attempt_number`, `user_id`,
  `confirmation_method=automatic`.
- `client_secret` chỉ xuất hiện trong response này và `client-data`; Payment mã hóa
  lưu, Order không lưu (`INV-PAY-013`).
- Replay cùng key sau khi attempt đã rời `PENDING` vẫn `200` với `status` hiện tại
  nhưng **không** có `client_secret`. Trong margin 30 giây trước scanner, replay cũng
  có thể trả `status=PENDING` không kèm client data; đọc `client-data` sẽ cho
  `usable=false`.
- `PAYMENT_INITIATED` được phát (Analytics), không kèm secret.
- Stripe xác định từ chối CREATE → operation `PROVIDER_REJECTED`; Payment giữ
  `INITIATING`, set `requires_manual_review`, mở case `PROVIDER_CREATE_REJECTED` và
  complete idempotency bằng `503 PROVIDER_REQUEST_REJECTED`, `retryable=false`.
  Replay cùng key trả cùng kết quả, không gọi Stripe lần nữa.

### 2.5. `GET /v1/payments/{transactionId}/client-data`

**Mục đích:** Order lấy client data mỗi lần cần relay (buyer reload, Checkout
recovery, replay checkpoint) thay vì lưu bản sao.

```json
{ "transaction_id": "TXN_01J9…", "payment_attempt_number": 1, "status": "PENDING",
  "usable": true, "client_secret": "pi_…_secret_…", "publishable_key": "pk_test_…",
  "client_expires_at": "2026-02-28T10:14:30Z" }
```

`usable=true` chỉ khi `status=PENDING` và `now < client_expires_at`; ngược lại
`usable=false`, `client_secret=null`, `publishable_key=null`, HTTP `200`
(`RULE-PAY-015`). Order tự authorize buyer là owner của order trước khi relay;
response luôn `private, no-store`.

### 2.6. `POST /v1/payments/{transactionId}/cancel`

**Mục đích:** Order lấy **fact** non-capture trước khi final-cancel và nhả
Inventory/Promotion ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).

```json
{ "order_id": "10001", "reason": "PAYMENT_TIMEOUT" }
```

| Tình huống | `outcome` | Nghĩa vụ của Order |
|---|---|---|
| `PENDING` → Stripe cancel `canceled` (`T5`) | `CANCELED` | Final-cancel, nhả resource |
| Đã `FAILED`/`EXPIRED`/`CANCELED` trước đó | `ALREADY_NON_CAPTURED` | Như trên |
| Stripe trả `succeeded` (capture thắng race, `T2`) | `ALREADY_SUCCEEDED` + `amount` + `financial_state_version` | **Không** final-cancel; đi lane hậu thanh toán/financial reconciliation; Payment mở review case `CAPTURE_AFTER_CANCEL` |
| Provider chưa cancel được `PROCESSING` hoặc outcome chưa rõ | `CANCEL_PENDING` (`200`) hoặc `503 PROVIDER_OUTCOME_UNKNOWN` | Giữ trạng thái cancelling; replay cùng key tới khi có outcome durable |

`reason` được ghi vào `ProviderOperation CANCEL` (`ORDER_COMMAND:<reason>`) và
`PAYMENT_CANCELED.reason_code`. Payment dùng cùng cơ chế cancel cho `T3`/`T4`, nên mọi
`PAYMENT_FAILED/EXPIRED/CANCELED` Order nhận đều là "intent đã hủy"
([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).

### 2.7. `POST /v1/payments/{transactionId}/refund`

**Mục đích:** một refund single-shot trọn đời/order sau Inventory evidence
([ADR-011](./decisions/ADR-011-one-lifetime-order-refund.md)).

```json
{ "order_id": "10001", "refund_request_id": "RRQ-10001-01",
  "amount": { "amount_minor": 400000, "currency": "VND" },
  "reason": "CUSTOMER_RETURN_SELECTED_LINES",
  "inventory_evidence": { "type": "RETURN_SCOPE_INSPECTION_COMPLETED",
    "refund_scope_id": "RS-…", "expected": 2, "received": 2, "passed": 1, "failed": 1 } }
```

Response `200`: `{ "refund_transaction_id": "RFD_01J…", "payment_transaction_id": "TXN_01J…", "status": "PROCESSING" }`.

Semantics:

- Precondition: payment `SUCCESS`; chưa có refund cho `order_id`; `0 < amount ≤ amount_minor`;
  Order đã chọn đúng một scope, mỗi selected line có `refund_quantity = ordered_quantity`.
  Không hỗ trợ partial quantity, incremental hay refund lần hai.
  `CUSTOMER_RETURN_SELECTED_LINES` bắt buộc evidence
  `RETURN_SCOPE_INSPECTION_COMPLETED {refund_scope_id, expected, received, passed, failed}`;
  `ADMIN_FORCE_REFUND_BEFORE_DISPATCH` bắt buộc evidence
  `COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH {release_reference}`. Các counter là số
  nguyên không âm; inspection `failed > 0` **không** phải lý do reject.
- Order `Idempotency-Key` chỉ dedup command. Payment truyền key namespace riêng
  `pay:refund:{refund_transaction_id}` sang Stripe và persist trong ProviderOperation.
- `Refund.create` với `payment_intent=pi_xxx`, `amount`, `reason=requested_by_customer`,
  metadata `order_id`, `refund_request_id`, `refund_transaction_id`.
- Response luôn `PROCESSING` (command đã được Payment durably accept ≠ refund final),
  kể cả khi Stripe trả `succeeded` hoặc deterministic reject ngay; Order chốt qua
  `REFUND_SUCCESS`/`REFUND_FAILED`/`REFUND_REVERSED` hoặc `GET /v1/refunds/{id}`.
- Cùng key → cùng response; key khác cho order đã có refund → `409`.

### 2.8. `GET /v1/payments/{transactionId}` và `GET /v1/refunds/{refundTransactionId}`

Read-only cho Order verify/recovery; đọc primary (read-your-writes).

```json
{ "transaction_id": "TXN_01J…", "order_id": "10001", "payment_attempt_number": 1,
  "amount": { "amount_minor": 995000, "currency": "VND" }, "status": "REFUNDED",
  "financial_state_version": 5, "requires_manual_review": false,
  "succeeded_at": "2026-02-28T10:07:00Z", "terminal_at": null,
  "refund": { "refund_transaction_id": "RFD_01J…", "payment_transaction_id": "TXN_01J…",
    "order_id": "10001", "amount": { "amount_minor": 400000, "currency": "VND" }, "status": "SUCCESS" } }
```

`refund` là object refund duy nhất của order hoặc `null`; `disputes` luôn là array
(có thể rỗng) theo PaymentDispute. Refund `status` ∈
`PENDING/PROCESSING/SUCCESS/FAILED/REVERSED`. Không có client data ở đây.

### 2.9. `POST /v1/payments/webhook/stripe`

Contract với Stripe, không phải với Order:

Stripe đăng ký URL public `POST /api/payments/webhook/stripe`. Gateway rewrite duy
nhất path sang service URL ở tiêu đề, không parse/serialize body và không đổi
`Stripe-Signature`; local development gọi thẳng service URL `/v1/...`.

| Khía cạnh | Quy tắc |
|---|---|
| Đăng ký | Một endpoint/môi trường; subscribe đúng 12 canonical event ở `operations.md §2.1` |
| Request | Raw JSON body + `Stripe-Signature: t=…,v1=…`; gateway giữ nguyên byte |
| Response | `400` khi thiếu/sai/quá hạn signature, malformed envelope hoặc body >256 KiB; không có inbox/Kafka. `200` chỉ sau inbox durable + Kafka ack (hoặc duplicate terminal). `503` khi inbox/Kafka unavailable để Stripe retry delivery mới |
| Xử lý | Ingress dùng `Webhook.constructEvent` trên exact bytes và tolerance 300s, parse field tối thiểu, encrypt raw/signature; worker chỉ xử lý verified delivery và kiểm hash |
| Duplicate | Dedup theo verified `stripe_event_id`; terminal → `200`; non-terminal vẫn publish/DB-retry để không nuốt lần xử lý lỗi |
| Out-of-order | Checkpoint `(event.created,event.id)` riêng trên payment/refund/dispute; stale no-op, tie xung đột → retrieve provider object |
| Kafka | Key `STRIPE:<stripe_event_id>`; structured verified CloudEvent theo internal AsyncAPI, có exact bytes base64 + SHA-256 + signing-secret id. Không có candidate/untrusted message |

Mapping từng event → transition ở §4.

## 3. Event phát ra — `t.payment.events.v2`

### 3.1. Envelope và delivery

CloudEvents 1.0 structured JSON (`application/cloudevents+json`), outbox → Debezium
→ Kafka, at-least-once, key bằng chính xác `data.order_id`
([ADR-007](./decisions/ADR-007-order-id-partition-key.md),
[ADR-015](./decisions/ADR-015-canonical-asyncapi-and-structured-cloudevents.md)).
Kafka value canonical là **toàn bộ envelope**; Kafka headers chỉ được mirror cho
observability và không phải authority của contract.

| Field | Giá trị |
|---|---|
| `specversion` | `"1.0"` |
| `id` | UUID = `outbox_events.event_id`; consumer dedup theo `(source,id)` |
| `source` | `/services/payment` |
| `type` | Exact namespaced type `.v2` ở §3.2; tên viết hoa chỉ là business alias |
| `subject` | `payments/{payment_transaction_id}` |
| `time` | thời điểm commit transition |
| `datacontenttype` | `application/json` |
| `dataschema` | URI schema riêng của đúng event trong `contracts/events/payment/v2/schemas/` |
| Extension | `correlationid`, `traceparent` ở top-level khi biết; không bắt buộc với fact bắt nguồn từ webhook/scheduler |
| Kafka headers | Có thể mirror ID/type/correlation/trace, nhưng consumer không được phụ thuộc |

`data` chung: `schema_version=2`, `order_id`, `payment_transaction_id`,
`payment_attempt_number`, `financial_state_version`; các field theo type ở §3.2.
`refund_transaction_id` xuất hiện với mọi `REFUND_*`.
`dispute_transaction_id` + `dispute_data` xuất hiện với mọi `DISPUTE_*`.

### 3.2. Danh mục event

| Business alias | CloudEvents `type` canonical | Transition | `data` thêm | Consumer & nghĩa vụ |
|---|---|---|---|---|
| `PAYMENT_INITIATED` | `com.ecommerce.payment.transaction.initiated.v2` | `T0.1` | `amount`, `occurred_at` (= thời điểm bind), `expires_at` | Analytics: funnel. Order không cần (đã có response HTTP) |
| `PAYMENT_SUCCESS` | `com.ecommerce.payment.transaction.succeeded.v2` | `T2` | `amount`, `occurred_at` (= `succeeded_at`) | **Order**: set `captured_payment_transaction_id` nếu null, `PAID`; nếu attempt khác active/đã có capture → financial reconciliation |
| `PAYMENT_FAILED` | `com.ecommerce.payment.transaction.failed.v2` | `T3` | `amount`, `occurred_at`, `error_code` (machine code chuẩn hóa) | **Order**: terminal non-capture fact, intent đã hủy → cancel + compensation hoặc mở attempt kế tiếp. Decline retryable **không** sinh event |
| `PAYMENT_EXPIRED` | `com.ecommerce.payment.transaction.expired.v2` | `T4` | `amount`, `occurred_at` | **Order**: như `PAYMENT_FAILED` |
| `PAYMENT_CANCELED` | `com.ecommerce.payment.transaction.canceled.v2` | `T5` | `amount`, `occurred_at`, `reason_code` (`PAYMENT_TIMEOUT`/`BUYER_CANCELLED`/`ORDER_CANCELLED_PRE_CAPTURE`/`PROVIDER_CANCELED`) | **Order**: hoàn tất pre-capture cancellation chỉ khi khớp transaction active |
| `REFUND_INITIATED` | `com.ecommerce.payment.refund.initiated.v2` | `T6` | `refund_transaction_id`, `refund_amount`, `occurred_at`, `reason_code` | Analytics; nghĩa là command đã được persist, chưa phải provider accepted |
| `REFUND_PROCESSING` | `com.ecommerce.payment.refund.processing.v2` | `R1` | `refund_transaction_id`, `refund_amount`, `occurred_at` (= `accepted_at`) | Order: `REFUNDING` (không phải final) |
| `REFUND_SUCCESS` | `com.ecommerce.payment.refund.succeeded.v2` | `R2`+`T7` | `refund_transaction_id`, `refund_amount`, `occurred_at` | **Order**: `RETURN_REQUESTED → REFUNDED` |
| `REFUND_FAILED` | `com.ecommerce.payment.refund.failed.v2` | `R3`+`T8` hoặc `R4`+`T8` | `refund_transaction_id`, `refund_amount`, `occurred_at`, `error_code` | Order: `REFUND_FAILED`/manual verification; SysAdmin alert; không tự refund lại |
| `REFUND_REVERSED` | `com.ecommerce.payment.refund.reversed.v2` | `R5`+`T9` | `refund_transaction_id`, `refund_amount`, `occurred_at`, `reason_code=REFUND_REVERSED_BY_PROVIDER` | Order: reopen manual verification; Accounting |
| `DISPUTE_OPENED` | `com.ecommerce.payment.dispute.opened.v2` | `D1` | `dispute_transaction_id`, `dispute_data`, `occurred_at` | **Order**: giữ payment/order hiện tại, mở financial reconciliation/manual review; không auto-refund |
| `DISPUTE_FUNDS_WITHDRAWN` | `com.ecommerce.payment.dispute.funds-withdrawn.v2` | `D3` | dispute + amount/status/funds state | **Order/Accounting**: ghi nhận exposure; không đổi Payment status |
| `DISPUTE_FUNDS_REINSTATED` | `com.ecommerce.payment.dispute.funds-reinstated.v2` | `D4` | dispute + amount/status/funds state | **Order/Accounting**: đóng exposure funds tương ứng |
| `DISPUTE_WON` | `com.ecommerce.payment.dispute.won.v2` | `D5a` | dispute + outcome | **Order**: resolve manual financial case khi version hợp lệ |
| `DISPUTE_LOST` | `com.ecommerce.payment.dispute.lost.v2` | `D5b` | dispute + outcome | **Order/Accounting**: giữ audit và xử lý loss; không tạo refund thứ hai |

Ví dụ `PAYMENT_SUCCESS`:

```json
{ "specversion": "1.0", "id": "123e4567-e89b-12d3-a456-426614174001",
  "type": "com.ecommerce.payment.transaction.succeeded.v2", "source": "/services/payment",
  "subject": "payments/TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "time": "2026-02-28T10:07:00Z",
  "datacontenttype": "application/json",
  "dataschema": "/contracts/events/payment/v2/schemas/payment-succeeded.schema.json",
  "data": { "schema_version": 2, "order_id": "10001",
    "payment_transaction_id": "TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "payment_attempt_number": 1,
    "amount": { "amount_minor": 995000, "currency": "VND" },
    "financial_state_version": 2, "occurred_at": "2026-02-28T10:07:00Z" } }
```

Ví dụ `REFUND_REVERSED`:

```json
{ "specversion": "1.0", "id": "123e4567-e89b-12d3-a456-426614174002",
  "type": "com.ecommerce.payment.refund.reversed.v2", "source": "/services/payment",
  "subject": "payments/TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "time": "2026-03-03T08:00:00Z",
  "datacontenttype": "application/json",
  "dataschema": "/contracts/events/payment/v2/schemas/refund-reversed.schema.json",
  "data": { "schema_version": 2, "order_id": "10001",
    "payment_transaction_id": "TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "payment_attempt_number": 1,
    "refund_transaction_id": "RFD_01J9ZB…", "refund_amount": { "amount_minor": 400000, "currency": "VND" },
    "financial_state_version": 6, "occurred_at": "2026-03-03T07:59:58Z",
    "reason_code": "REFUND_REVERSED_BY_PROVIDER" } }
```

### 3.3. Nghĩa vụ consumer (Order)

1. Dedup theo CloudEvents `(source,id)` bằng durable inbox (`order_consumed_events`) trong
   cùng transaction với mutation.
2. Validate `order_id` thuộc mình, `payment_transaction_id`/`payment_attempt_number`
   khớp `order_payment_attempts`, `amount.currency=VND`, amount khớp snapshot.
3. `financial_state_version` phải > `last_financial_state_version` của **cùng**
   transaction; nhỏ hơn/bằng → bỏ qua (đã xử lý hoặc out-of-order); không so sánh
   giữa hai attempt.
4. Không suy luận: `PAYMENT_FAILED/EXPIRED/CANCELED` cho attempt không active → ghi
   nhận, không đổi trạng thái đơn; `PAYMENT_SUCCESS` cho attempt không active hoặc
   khi đã có capture → financial reconciliation, không thay `captured_payment_transaction_id`.
5. `REFUND_PROCESSING` không phải final; chỉ `REFUND_SUCCESS/FAILED/REVERSED` là
   terminal signal.
6. Dispute facts không đổi trạng thái Payment/Order bằng suy luận; chúng mở/cập nhật
   financial reconciliation case theo `dispute_transaction_id` và version.
7. Field lạ → bỏ qua; thiếu field bắt buộc → park + alert, không crash consumer.

### 3.4. Các field chủ đích không có

`client_secret`, `user_id`, `provider`, `provider_transaction_id` (`pi_`), `ch_`,
`re_`, `dp_`, `evt_`, provider balance transaction id, `payment_method*`,
`error_message`, `reason_message`, sequence id
([ADR-008](./decisions/ADR-008-minimal-event-payload-phase-20.md)). Analytics join
qua `order_id`. Notification không consume topic này vì payload chủ đích không có
recipient; nếu cần thông báo phải có notification-request contract riêng.

## 4. Event tiêu thụ — Stripe webhook (nội bộ qua `t.payment.webhooks.raw`)

Wire contract là [Payment internal webhook v1](../../contracts/events/payment/internal/v1/asyncapi.yaml):
`payment-api` verify exact raw body trước, persist durable inbox, rồi base64-encode
delivery đã xác thực vào `com.ecommerce.payment.stripe-webhook.received.v1`;
`payment-worker` đối chiếu SHA-256 và claim inbox bằng lease/fencing. Retry claim DB
row, không re-verify signature recency. Payment không consume `t.order.events`.

| Stripe event | Điều kiện | Xử lý | Fact phát |
|---|---|---|---|
| `payment_intent.processing` | payment `PENDING` | `T1` | — |
| `payment_intent.succeeded` | payment `PENDING`/`PROCESSING` | `T2`; ghi `ch_xxx`, `succeeded_at`; xóa client secret | `PAYMENT_SUCCESS` |
| `payment_intent.succeeded` | payment đã terminal non-capture | Không transition; review case `CAPTURE_AFTER_CANCEL` | — |
| `payment_intent.payment_failed` | decline retryable, `decline_count < max` | `T3a`: `last_error_*`, `decline_count++`, giữ `PENDING` | — |
| `payment_intent.payment_failed` | non-retryable hoặc quá ngưỡng | `CANCEL(DECLINED_*)` operation → `PaymentIntent.cancel` → `T3` | `PAYMENT_FAILED` |
| `payment_intent.canceled` | có `CANCEL` operation | terminal theo reason (`T3`/`T4`/`T5`); thường no-op vì response sync đã áp | fact tương ứng nếu chưa phát |
| `payment_intent.canceled` | không có operation (Stripe/Dashboard hủy) | `T5` với `reason_code=PROVIDER_CANCELED` | `PAYMENT_CANCELED` |
| `charge.refunded` | refund `PROCESSING` | Chỉ là tín hiệu (`data.object` là charge): `Refund.retrieve` rồi áp theo `Refund.status` bên dưới; không tự `R2` | theo kết quả retrieve |
| `refund.updated` (`status=succeeded`) | refund `PROCESSING` | `R2`+`T7` | `REFUND_SUCCESS` |
| `refund.updated` (`status=pending`) | refund `PROCESSING` | Ignore-and-advance checkpoint; cập nhật snapshot | — |
| `refund.updated` / `refund.failed` (`status` ∈ {`failed`, `canceled`}) | refund `PROCESSING` | `R3`+`T8`; review case | `REFUND_FAILED` (`error_code` = `failure_reason` chuẩn hóa; `canceled` → `MERCHANT_REQUEST`) |
| `refund.updated` / `refund.failed` (`status` ∈ {`failed`, `canceled`, `requires_action`}) | refund `SUCCESS` | `R5`+`T9`; review case | `REFUND_REVERSED` |
| `refund.updated` (`status=requires_action`) | refund `PROCESSING` | Giữ `PROCESSING`; ReviewCase `RECONCILIATION_DRIFT` | — |
| `charge.dispute.created` | map được payment | `D1`; dispute + review case | `DISPUTE_OPENED` |
| `charge.dispute.updated` | dispute active | `D2`, deadline/status audit-only | — |
| `charge.dispute.funds_withdrawn` | map được dispute/payment | `D3` + journal | `DISPUTE_FUNDS_WITHDRAWN` |
| `charge.dispute.funds_reinstated` | map được dispute/payment | `D4` + journal | `DISPUTE_FUNDS_REINSTATED` |
| `charge.dispute.closed` | won/lost/warning_closed | `D5a`/`D5b`/`D5c` | `DISPUTE_WON`/`DISPUTE_LOST`/— |
| khác / không map được | — | `IGNORED` | — |

Guard trước mọi transition: delivery đã được ingress xác thực; worker kiểm hash;
`data.object` map qua `pi_`/`ch_`/`re_`/`dp_` hoặc metadata; amount/currency khớp.
Ordering theo checkpoint aggregate, không chỉ state precedence.
Khi cùng `created` nhưng khác event ID, worker gọi đúng
`PaymentIntent.retrieve`/`Refund.retrieve`/`Dispute.retrieve`: fact lifecycle hiện
tại được áp với event ID gốc để advance checkpoint; trạng thái chưa tạo transition
được ignore-and-advance. Tie của hai dispute funds movement mở
`DISPUTE_FUNDS_TIE_UNRESOLVED` để balance-ledger reconciliation xử lý, tuyệt đối
không đoán chiều tiền từ dispute lifecycle status.
## 5. Stripe API — contract outbound

| Call | Khi | Idempotency key | Metadata bắt buộc | Kết quả map |
|---|---|---|---|---|
| `PaymentIntent.create` | initiate | `pay:create:{TXN}` | `payment_transaction_id`, `order_id`, `payment_attempt_number`, `user_id` | `pi_xxx` + `client_secret` → `T0.1` |
| `PaymentIntent.cancel` | cancel command, scanner, decline terminate | `pay:cancel:{TXN}` (một per payment) | — | actual `canceled` → `T3`/`T4`/`T5`; `succeeded` → `T2`; chưa rõ → recovery |
| `PaymentIntent.retrieve` | recovery, reconciliation | — | — | `provider_status_snapshot`; bind khi recovery |
| `Refund.create` | refund command | `pay:refund:{RFD}` | `order_id`, `refund_request_id`, `refund_transaction_id` | `re_xxx` → `R1` (+`R2` nếu `succeeded`) |
| `Refund.retrieve` | poller, reconciliation | — | — | `R2`/`R3`/`R5` |
| `Dispute.retrieve` | tie-break/reconciliation/evidence ops | — | — | `D1`…`D5` snapshot |
| `BalanceTransaction.list/retrieve` | balance reconciliation | — | — | journal capture/refund/dispute/fee/payout |
| `Webhook.constructEvent` | ingress | — | — | verify + parse trong 300s |
| `PaymentIntent/Refund search/list/retrieve` theo metadata | recovery qua safety horizon | — | — | bind duy nhất hoặc review case; không blind-create |

Quy tắc: API version pin `2026-08-26.dahlia` với `stripe-java 33.4.2`
([ADR-021](./decisions/ADR-021-pin-stripe-java-and-api-version.md)); mọi call có timeout 15s; lỗi map
thành `ProviderOutcome` `CONFIRMED`/`REJECTED`/`UNKNOWN` — `UNKNOWN` cho timeout,
5xx, network; `REJECTED` cho 4xx có `error.code` (kể cả `idempotency_error`); không
call create/cancel/refund nào thiếu ProviderOperation + DB lease/fencing hợp lệ;
trước horizon retry đúng key, sau horizon chỉ lookup/reconcile (`INV-PAY-011`).

## 6. Tương thích và tiến hóa

- HTTP v1 và events v2 chỉ thay đổi **additive** sau khi consumer readiness đã được
  xác nhận; consumer phải ignore optional field chưa biết. Đổi nghĩa/xóa field,
  đổi exact `type` hoặc làm optional thành required → version/topic mới.
- Thay đổi 2026-09 (additive): `GET …/client-data`; `REVERSED` trong refund status;
  `PaymentStatus` thêm `requires_manual_review`, `succeeded_at`, `terminal_at`,
  `refund`, `disputes`; thêm 5 dispute facts. `PAYMENT_FAILED` chỉ phát sau khi intent hủy.
- Thay đổi 2026-09-30 ([ADR-022](./decisions/ADR-022-refund-lifecycle-hardening.md), không
  đổi wire contract): `R2` chỉ từ `Refund.status=succeeded`; `canceled`/`requires_action`
  được ánh xạ; `REFUND_FAILED.error_code` có thêm `MERCHANT_REQUEST` và đủ 7 giá trị
  `failure_reason` (đã hợp lệ với `machine_code`).
- Consumer dispatch theo exact namespaced `type`, không theo business alias viết hoa,
  và phải reject/park event thiếu `payment_transaction_id`, `amount`/`refund_amount`,
  `financial_state_version`, hoặc có `type` không khớp `dataschema`.
- Stripe: đổi API version qua ADR; webhook payload có thể đổi shape theo version →
  test fixture theo version pin.
- Gate `verified`: provider contract test (OpenAPI/AsyncAPI ↔ implementation),
  producer schema/fixture test cho cả 15 event, consumer test phía Order cho 13 exact
  type mà Order đăng ký, breaking-change check trong CI; chưa có.

## 7. Phụ thuộc chéo đã đồng bộ

`docs/mesh/runtime-contract.md` (AuthorizationPolicy có `GET`), `docs/gateway/runtime-contract.md`
(chỉ route webhook), `contracts/catalog/interactions.yaml` (notes client-data, ADR-014),
`docs/order/data.md` (không lưu client data), `docs/checkout` ADR-010 (không cache secret) đã
được cập nhật cùng bộ tài liệu này.

### Bằng chứng correction SUCCESS sau audit 2026-10-11

Theo quyết định chủ dự án, PAYMENT_SUCCESS_CONFIRMED yêu cầu provider_status=succeeded,
provider_object_id là Charge ch_, provider_response_json là object có id bằng
PaymentIntent đã lưu, status=succeeded và latest_charge bằng Charge đó. Charge đã
lưu nếu có cũng phải khớp. Journal CAPTURE dùng source CHARGE/ch_ chung với webhook
và balance, tránh journal CHARGE/pi_ gây ghi kép. Bằng chứng do Finance Ops cung cấp;
API không tự retrieve hoặc xác thực chữ ký của bằng chứng.
