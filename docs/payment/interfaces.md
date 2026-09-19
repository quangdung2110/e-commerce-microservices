---
doc_id: payment-interfaces
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence:
  - contracts/http/payment/v1/openapi.yaml
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/catalog/interactions.yaml
verified_against_commit: null
---

# Payment Service — Giao diện tích hợp

> Contract chuẩn trên wire là [Payment HTTP v1](../../contracts/http/payment/v1/openapi.yaml)
> và [Payment events v2](../../contracts/events/payment/v2/asyncapi.yaml). File này
> giải thích **semantics** (idempotency, lỗi, nghĩa vụ consumer, ví dụ); khi lệch,
> contract file thắng và file này phải sửa theo. Ví dụ JSON minh họa, không phải
> fixture test.

## 1. Danh mục tương tác

| Interaction ID (catalog) | Hướng | Giao thức | Provider → Consumer | Auth / transport | Contract |
|---|---|---|---|---|---|
| `order-initiates-payment` | Inbound | HTTP/JSON | Payment ← Order (Temporal Activities) | Mesh mTLS, principal `ecommerce/order-service`; timeout 15s, mesh **không retry** | OpenAPI v1: initiate, cancel, refund, `GET` payment/client-data/refund |
| Stripe webhook | Inbound | HTTPS | Payment ← Stripe | Gateway `stripe-webhook` (bypass JWT, raw body, rate limit); `Stripe-Signature` verify ở worker | Stripe event schema (API version pin) |
| `payment-publishes-payment-events` | Outbound | Kafka `t.payment.events.v2` | Payment → Order, Analytics | Outbox CDC, at-least-once, key `order_id` | AsyncAPI v2 |
| `t.payment.webhooks.raw` | Internal | Kafka | Payment → Payment Webhook Processor | Nội bộ, retention 7 ngày | Không public |
| Stripe API | Outbound | HTTPS (`stripe-java`) | Stripe ← Payment | Secret key, egress `ServiceEntry`, idempotency key per operation | Stripe API (pin version) |

Payment **không** có route cho buyer/CSKH; mọi read đi qua Order. Payment không
consume topic nào của service khác ([ADR-010](./decisions/ADR-010-refund-only-by-explicit-order-command.md)).

## 2. HTTP API v1

### 2.1. Quy ước chung

| Khía cạnh | Quy tắc |
|---|---|
| Base path | `/v1` nội bộ mesh; không expose qua gateway |
| Xác thực | Mesh mTLS + `AuthorizationPolicy`; header `X-Service-Identity: order-service` chỉ để attribution/log |
| Correlation | `X-Correlation-Id` (Order truyền Temporal Workflow ID); Payment echo lại và ghi vào `outbox_events.headers` |
| Idempotency | Header `Idempotency-Key` (16–255 ký tự) bắt buộc với `POST`; một key ↔ một `(action, fingerprint)`; xem §2.2 |
| Money | `{ "amount_minor": <int64>, "currency": "VND" }`; VND zero-decimal, không nhân/chia |
| ID | Opaque string ≤ 128; Payment sinh `TXN_<ULID>`, `RFD_<ULID>`; Order gửi `order_id` dạng chuỗi số |
| Thời gian | RFC 3339 UTC |
| Lỗi | `application/problem+json` `{ code, retryable, correlation_id }`; `retryable` là authority cho caller |
| Cache | Mọi response `Cache-Control: private, no-store` |
| Timeout | Caller đặt ≤ 15s (mesh); Payment tự cắt Stripe ở 15s read timeout để không vượt |

### 2.2. Idempotency của command

Payment lưu `payment_idempotency (idempotency_key, action, request_fingerprint,
status)` trước khi xử lý (`data.md §3.7`):

| Tình huống | Kết quả |
|---|---|
| Key mới | Xử lý; lưu `COMPLETED` + response |
| Cùng key, cùng fingerprint, `COMPLETED` | Trả lại response đã lưu (initiate: client data được giải mã lại nếu còn dùng được) |
| Cùng key, `IN_PROGRESS` | `409 REQUEST_IN_PROGRESS`, `retryable=true` (Order retry sau vài giây) |
| Cùng key, fingerprint khác | `422 IDEMPOTENCY_KEY_REUSED`, `retryable=false` |
| Provider outcome chưa rõ | `503 PROVIDER_OUTCOME_UNKNOWN`, `retryable=true`; key vẫn `IN_PROGRESS`; Order **phải** retry cùng key, không đổi attempt |

Fingerprint = SHA-256 của body canonical (sort key, bỏ whitespace). Redis chỉ là
fast-path; mất Redis không đổi kết quả.

### 2.3. Bảng lỗi

| `code` | HTTP | `retryable` | Khi nào |
|---|---|---|---|
| `IDEMPOTENCY_KEY_MISSING` | 400 | false | Thiếu header trên `POST` |
| `VALIDATION_FAILED` | 400 | false | Schema/enum sai, amount ≤ 0 |
| `PAYMENT_NOT_SUCCESS` | 400 | false | Refund trên payment không `SUCCESS` |
| `REFUND_AMOUNT_EXCEEDED` | 400 | false | `amount > amount_minor` |
| `ORDER_MISMATCH` | 400 | false | `order_id` không khớp transaction |
| `PAYMENT_NOT_FOUND` / `REFUND_NOT_FOUND` | 404 | false | ID không tồn tại |
| `REQUEST_IN_PROGRESS` | 409 | true | Cùng key đang xử lý |
| `ORDER_REFUND_ALREADY_EXISTS` | 409 | false | Order đã có refund với key khác |
| `ATTEMPT_ALREADY_EXISTS` | 409 | false | `(order_id, payment_attempt_number)` đã có với key khác |
| `IDEMPOTENCY_KEY_REUSED` | 422 | false | Cùng key khác payload |
| `PROVIDER_OUTCOME_UNKNOWN` | 503 | true | Stripe timeout/ambiguous sau khi đã commit operation |
| `PROVIDER_UNAVAILABLE` | 503 | true | Stripe lỗi trước khi gọi (5xx, network) |
| `DEPENDENCY_UNAVAILABLE` | 503 | true | PostgreSQL/KMS không sẵn sàng |

`PAYMENT_NOT_CANCELABLE` không là lỗi: cancel khi `PROCESSING` trả `200` với
`outcome=CANCEL_PENDING`.

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

Response `200` khi provider chưa bind (crash/timeout đã được recovery nhận):

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
  nhưng **không** có `client_secret` (đọc `client-data` để biết `usable=false`).
- `PAYMENT_INITIATED` được phát (Analytics), không kèm secret.

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
| `PROCESSING` (Stripe không cho cancel) hoặc outcome chưa rõ | `CANCEL_PENDING` (`200`) hoặc `503 PROVIDER_OUTCOME_UNKNOWN` | Giữ trạng thái cancelling; replay cùng key tới khi có outcome durable |

`reason` được ghi vào `ProviderOperation CANCEL` (`ORDER_COMMAND:<reason>`) và
`PAYMENT_CANCELED.reason_code`. Payment dùng cùng cơ chế cancel cho `T3`/`T4`, nên mọi
`PAYMENT_FAILED/EXPIRED/CANCELED` Order nhận đều là "intent đã hủy"
([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).

### 2.7. `POST /v1/payments/{transactionId}/refund`

**Mục đích:** một refund trọn đời/order sau Inventory evidence
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

- Precondition: payment `SUCCESS`; chưa có refund cho `order_id`; `amount ≤ amount_minor`;
  `inventory_evidence` thuộc một trong hai dạng (`RETURN_SCOPE_INSPECTION_COMPLETED`
  hoặc `COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH`). Inspection `failed > 0` **không**
  phải lý do reject.
- `Idempotency-Key` được truyền nguyên sang Stripe (`RequestOptions.setIdempotencyKey`)
  và là `provider_operations.provider_idempotency_key` của operation `REFUND`.
- `Refund.create` với `payment_intent=pi_xxx`, `amount`, `reason=requested_by_customer`,
  metadata `order_id`, `refund_request_id`, `refund_transaction_id`.
- Response luôn `PROCESSING` (Accepted ≠ Final) kể cả khi Stripe trả `succeeded`
  ngay; Order chốt qua `REFUND_SUCCESS`/`REFUND_FAILED`/`REFUND_REVERSED` hoặc
  `GET /v1/refunds/{id}`.
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

`refund` là object refund duy nhất của order hoặc `null`. Refund `status` ∈
`PENDING/PROCESSING/SUCCESS/FAILED/REVERSED`. Không có client data ở đây.

### 2.9. `POST /v1/payments/webhook/stripe`

Contract với Stripe, không phải với Order:

| Khía cạnh | Quy tắc |
|---|---|
| Đăng ký | Một endpoint/môi trường; subscribe đúng 8 event ở `operations.md §2.1` |
| Request | Raw JSON body + `Stripe-Signature: t=…,v1=…`; gateway giữ nguyên byte |
| Response | `200` sau khi Kafka producer ack (< 500ms); `503` nếu không produce được → Stripe retry (tới 3 ngày) |
| Xử lý | Không verify/parse tại endpoint; verify ở Webhook Processor (`Webhook.constructEvent`, cửa sổ 300s) |
| Duplicate | Dedup theo `stripe_event_id` sau verify; duplicate → `200`, không transition |
| Out-of-order | State precedence theo `event.created`; không override terminal |
| Kafka | Key `STRIPE:<stripe_event_id>` (parse chưa verify, chỉ để partition), value `{provider, stripe_event_id, raw_body, stripe_signature, received_at}` |

Mapping từng event → transition ở §4.

## 3. Event phát ra — `t.payment.events.v2`

### 3.1. Envelope và delivery

CloudEvents 1.0 JSON (`application/cloudevents+json`), outbox → Debezium → Kafka,
at-least-once, key = `order_id` ([ADR-007](./decisions/ADR-007-order-id-partition-key.md)).

| Field | Giá trị |
|---|---|
| `specversion` | `"1.0"` |
| `id` | UUID = `outbox_events.event_id`; consumer dedup theo đây |
| `source` | `payment-service` |
| `type` | một trong 10 type ở §3.2 |
| `subject` | `payments/{payment_transaction_id}` |
| `time` | thời điểm commit transition |
| `datacontenttype` | `application/json` |
| Kafka headers | `ce_id`, `ce_type`, `correlation_id`, `traceparent` (từ `outbox_events.headers`) |

`data` chung: `schema_version=2`, `order_id`, `payment_transaction_id`,
`payment_attempt_number`, `financial_state_version`; các field theo type ở §3.2.
`refund_transaction_id` xuất hiện với mọi `REFUND_*`.

### 3.2. Danh mục event

| `type` | Transition | `data` thêm | Consumer & nghĩa vụ |
|---|---|---|---|
| `PAYMENT_INITIATED` | `T0.1` | `amount`, `occurred_at` (= thời điểm bind), `expires_at` | Analytics: funnel. Order không cần (đã có response HTTP) |
| `PAYMENT_SUCCESS` | `T2` | `amount`, `occurred_at` (= `succeeded_at`) | **Order**: set `captured_payment_transaction_id` nếu null, `PAID`; nếu attempt khác active/đã có capture → financial reconciliation. Có thể đến khi workflow checkout đã đóng → signal financial-recovery |
| `PAYMENT_FAILED` | `T3` | `amount`, `occurred_at`, `error_code` (decline code chuẩn hóa hoặc `DECLINE_LIMIT_EXCEEDED`) | **Order**: terminal non-capture fact, intent đã hủy → cancel + compensation hoặc mở attempt kế tiếp. Decline retryable **không** sinh event |
| `PAYMENT_EXPIRED` | `T4` | `amount`, `occurred_at` | **Order**: như `PAYMENT_FAILED` |
| `PAYMENT_CANCELED` | `T5` | `amount`, `occurred_at`, `reason_code` (`PAYMENT_TIMEOUT`/`BUYER_CANCELLED`/`ORDER_CANCELLED_PRE_CAPTURE`/`PROVIDER_CANCELED`) | **Order**: hoàn tất pre-capture cancellation chỉ khi khớp `payment_transaction_id` active |
| `REFUND_INITIATED` | `T6` | `refund_transaction_id`, `refund_amount`, `occurred_at`, `reason_code` (= `reason` request) | Analytics |
| `REFUND_PROCESSING` | `R1` | `refund_transaction_id`, `refund_amount`, `occurred_at` (= `accepted_at`) | Order: `REFUNDING` (không phải final) |
| `REFUND_SUCCESS` | `R2`+`T7` | `refund_transaction_id`, `refund_amount`, `occurred_at` | **Order**: `RETURN_REQUESTED → REFUNDED`, `refunded_at` |
| `REFUND_FAILED` | `R3`+`T8` hoặc `R4`+`T8` | `refund_transaction_id`, `refund_amount`, `occurred_at`, `error_code` (Stripe `failure_reason` chuẩn hóa) | Order: `REFUND_FAILED`/manual verification; SysAdmin alert. Payment không tự refund lại |
| `REFUND_REVERSED` | `R5`+`T9` | `refund_transaction_id`, `refund_amount`, `occurred_at`, `reason_code=REFUND_REVERSED_BY_PROVIDER` | Order: reopen case về manual verification; Accounting. Tiền đã về merchant, khách chưa nhận |

Ví dụ `PAYMENT_SUCCESS`:

```json
{ "specversion": "1.0", "id": "123e4567-e89b-12d3-a456-426614174001",
  "type": "PAYMENT_SUCCESS", "source": "payment-service",
  "subject": "payments/TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "time": "2026-02-28T10:07:00Z",
  "datacontenttype": "application/json",
  "data": { "schema_version": 2, "order_id": "10001",
    "payment_transaction_id": "TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "payment_attempt_number": 1,
    "amount": { "amount_minor": 995000, "currency": "VND" },
    "financial_state_version": 2, "occurred_at": "2026-02-28T10:07:00Z" } }
```

Ví dụ `REFUND_REVERSED`:

```json
{ "specversion": "1.0", "id": "…", "type": "REFUND_REVERSED", "source": "payment-service",
  "subject": "payments/TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "time": "2026-03-03T08:00:00Z",
  "datacontenttype": "application/json",
  "data": { "schema_version": 2, "order_id": "10001",
    "payment_transaction_id": "TXN_01J9Z3M4R8Q2K7V6W5X1Y0ZABC", "payment_attempt_number": 1,
    "refund_transaction_id": "RFD_01J9ZB…", "refund_amount": { "amount_minor": 400000, "currency": "VND" },
    "financial_state_version": 6, "occurred_at": "2026-03-03T07:59:58Z",
    "reason_code": "REFUND_REVERSED_BY_PROVIDER" } }
```

### 3.3. Nghĩa vụ consumer (Order)

1. Dedup theo CloudEvents `id` bằng durable inbox (`order_consumed_events`) trong
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
6. Field lạ → bỏ qua; thiếu field bắt buộc → park + alert, không crash consumer.

### 3.4. Các field chủ đích không có

`client_secret`, `user_id`, `provider`, `provider_transaction_id` (`pi_`), `ch_`,
`re_`, `evt_`, `payment_method*`, `error_message`, `reason_message`, sequence id
([ADR-008](./decisions/ADR-008-minimal-event-payload-phase-20.md)). Analytics join
qua `order_id`; Notification map `error_code`/`reason_code` sang i18n.

## 4. Event tiêu thụ — Stripe webhook (nội bộ qua `t.payment.webhooks.raw`)

Consumer group `payment-webhook-processor`. Payment không consume `t.order.events`.

| Stripe event | Điều kiện | Xử lý | Fact phát |
|---|---|---|---|
| `payment_intent.processing` | payment `PENDING` | `T1` | — |
| `payment_intent.succeeded` | payment `PENDING`/`PROCESSING` | `T2`; ghi `ch_xxx`, `succeeded_at`; xóa client secret | `PAYMENT_SUCCESS` |
| `payment_intent.succeeded` | payment đã terminal non-capture | Không transition; review case `CAPTURE_AFTER_CANCEL` | — |
| `payment_intent.payment_failed` | decline retryable, `decline_count < max` | `T3a`: `last_error_*`, `decline_count++`, giữ `PENDING` | — |
| `payment_intent.payment_failed` | non-retryable hoặc quá ngưỡng | `CANCEL(DECLINED_*)` operation → `PaymentIntent.cancel` → `T3` | `PAYMENT_FAILED` |
| `payment_intent.canceled` | có `CANCEL` operation | terminal theo reason (`T3`/`T4`/`T5`); thường no-op vì response sync đã áp | fact tương ứng nếu chưa phát |
| `payment_intent.canceled` | không có operation (Stripe/Dashboard hủy) | `T5` với `reason_code=PROVIDER_CANCELED` | `PAYMENT_CANCELED` |
| `charge.refunded` | refund `PROCESSING` | `R2`+`T7` | `REFUND_SUCCESS` |
| `charge.refund.updated` / `refund.failed` (`status=failed`) | refund `PROCESSING` | `R3`+`T8`; review case | `REFUND_FAILED` |
| `charge.refund.updated` / `refund.failed` (`status=failed`) | refund `SUCCESS` | `R5`+`T9`; review case | `REFUND_REVERSED` |
| `charge.dispute.created` | payment `SUCCESS`/`REFUNDED` | review case `DISPUTE_OPENED` | — |
| khác / không map được | — | `IGNORED` | — |

Guard trước mọi transition: signature hợp lệ; `data.object` map về transaction qua
`pi_`/`ch_`/`re_` hoặc metadata `payment_transaction_id`; amount/currency khớp (lệch
→ `AMOUNT_MISMATCH` + review case). Precedence theo `event.created`.

## 5. Stripe API — contract outbound

| Call | Khi | Idempotency key | Metadata bắt buộc | Kết quả map |
|---|---|---|---|---|
| `PaymentIntent.create` | initiate | `provider_operations.provider_idempotency_key` (`create:{TXN}`) | `payment_transaction_id`, `order_id`, `payment_attempt_number`, `user_id` | `pi_xxx` + `client_secret` → `T0.1` |
| `PaymentIntent.cancel` | cancel command, scanner, decline terminate | `cancel:{TXN}` (một per payment) | — | `canceled` → `T3`/`T4`/`T5`; `succeeded` → `T2`; `processing` → giữ |
| `PaymentIntent.retrieve` | recovery, reconciliation | — | — | `provider_status_snapshot`; bind khi recovery |
| `Refund.create` | refund command | `Idempotency-Key` của Order | `order_id`, `refund_request_id`, `refund_transaction_id` | `re_xxx` → `R1` (+`R2` nếu `succeeded`) |
| `Refund.retrieve` | poller, reconciliation | — | — | `R2`/`R3`/`R5` |
| `Webhook.constructEvent` | processor | — | — | verify + parse |
| `PaymentIntent.search`/`list` theo metadata | recovery không bind được | — | — | bind hoặc review case |

Quy tắc: API version pin (`operations.md §2.1`); mọi call có timeout 15s; lỗi map
thành `ProviderOutcome` `CONFIRMED`/`REJECTED`/`UNKNOWN` — `UNKNOWN` cho timeout,
5xx, network; `REJECTED` cho 4xx có `error.code` (kể cả `idempotency_error`); không
call nào thiếu `ProviderOperation` đã commit (`INV-PAY-011`).

## 6. Tương thích và tiến hóa

- HTTP v1 và events v2 chỉ thay đổi **additive**: thêm field optional, thêm enum
  value chỉ khi consumer đã ignore unknown (Order phải park thay vì crash). Đổi
  nghĩa/xóa field → version mới (`/v2`, `t.payment.events.v3`).
- Thay đổi 2026-09 (additive): `GET …/client-data`; `REVERSED` trong refund status;
  `PaymentStatus` thêm `requires_manual_review`, `succeeded_at`, `terminal_at`,
  `refund`; `PAYMENT_FAILED` chỉ phát sau khi intent hủy (semantics, không đổi schema).
- Consumer phải reject/park event thiếu `payment_transaction_id`, `amount`/`refund_amount`,
  `financial_state_version`.
- Stripe: đổi API version qua ADR; webhook payload có thể đổi shape theo version →
  test fixture theo version pin.
- Gate `verified`: provider contract test (OpenAPI/AsyncAPI ↔ implementation),
  consumer test phía Order cho 10 event, breaking-change check trong CI; chưa có.

## 7. Phụ thuộc chéo đã đồng bộ

`docs/mesh/runtime-contract.md` (AuthorizationPolicy có `GET`), `docs/gateway/runtime-contract.md`
(chỉ route webhook), `contracts/catalog/interactions.yaml` (notes client-data, ADR-014),
`docs/order/data.md` (không lưu client data), `docs/checkout` ADR-010 (không cache secret) đã
được cập nhật cùng bộ tài liệu này.
