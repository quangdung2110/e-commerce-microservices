---
doc_id: payment-interfaces
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Giao diện tích hợp

> Contract chuẩn trên wire là [Payment HTTP v1](../../contracts/http/payment/v1/openapi.yaml)
> và [Payment events v2](../../contracts/events/payment/v2/asyncapi.yaml). File này
> giải thích semantics vận hành; không được dùng ví dụ Markdown để ghi đè contract.

## 1. Danh mục tương tác

| Tương tác | Hướng | Giao thức | Provider | Consumer | Trạng thái |
|---|---|---|---|---|---|
| Initiate payment | Inbound | HTTP | Payment | Order (`InitiatePaymentActivity`) | [OpenAPI v1](../../contracts/http/payment/v1/openapi.yaml) |
| Cancel pending payment | Inbound | HTTP | Payment | Order (`CancelPaymentActivity`) | [OpenAPI v1](../../contracts/http/payment/v1/openapi.yaml) |
| Initiate refund | Inbound | HTTP | Payment | Order (`OrderRefundWorkflow`) | [OpenAPI v1](../../contracts/http/payment/v1/openapi.yaml) |
| Query payment status | Inbound | HTTP | Payment | Order verify | [OpenAPI v1](../../contracts/http/payment/v1/openapi.yaml) |
| Query refund status | Inbound | HTTP | Payment | Order verify | [OpenAPI v1](../../contracts/http/payment/v1/openapi.yaml) |
| Stripe webhook | Inbound | HTTPS | Payment | Stripe | proposed |
| `t.payment.events.v2` | Outbound | Kafka | Payment | Order, Analytics | [AsyncAPI v2](../../contracts/events/payment/v2/asyncapi.yaml) |
| `t.order.events` | Inbound | Kafka | Order | Payment (`payment-order-consumer`) | proposed semantics |
| `t.payment.webhooks.raw` | Internal | Kafka | Payment | Payment Webhook Processor | nội bộ |
| Stripe API | Outbound | HTTPS (`stripe-java`) | Stripe | Payment | Stripe API |

## 2. HTTP API

REST/JSON là protocol chuẩn cho mọi command Payment trong Release 1. Không có gRPC
fallback hay `.proto`; client không được tự chọn protocol khác. Mọi command tài chính
chỉ được gọi bởi Order Service qua workload identity.

| API | Mục đích và semantics | Idempotency/lỗi chính |
|---|---|---|
| `POST /v1/payments/initiate` | Order Temporal Activity persists/replays one create operation then binds one Stripe PaymentIntent; returns client data only after provider identity is proven | Order `Idempotency-Key` + durable `ProviderOperation` are authority; Redis SETNX is only concurrency fast-path |
| `POST /v1/payments/{transactionId}/cancel` | Order yêu cầu dừng một payment chưa capture **trước** khi Order công bố `ORDER_CANCELLED`; Payment là authority cho race cancel/capture | Header `Idempotency-Key` bắt buộc. Kết quả durable là `CANCELED`/`ALREADY_NON_CAPTURED` hoặc `ALREADY_SUCCEEDED`; timeout chỉ được retry/query với cùng key, không được coi là cancel thành công. |
| `POST /v1/payments/webhook/stripe` | Endpoint cố định nhận webhook Stripe (không còn path variable `{provider}`); trả `200` < 500ms; dump raw vào Kafka | Dedup ở worker theo `stripe_event_id` và `(provider, provider_transaction_id, event_type)` |
| `GET /v1/payments/{transactionId}` | Trả chi tiết transaction kèm danh sách refund (`LEFT JOIN refund_transactions`) cho Order verification | Read-only; chỉ Order workload; `200` |
| `POST /v1/payments/{transactionId}/refund` | Order khởi tạo one lifetime refund sau Inventory evidence; validate `SUCCESS`, unique order và amount; gọi Stripe `Refund.create`; trả `PROCESSING` | Header `Idempotency-Key` bắt buộc; trùng key → cùng kết quả; key khác sau existing order refund → `409` |
| `GET /v1/refunds/{refundTransactionId}` | Đọc trạng thái refund độc lập với read model Payment Transaction cho Order; hỗ trợ query timeline khi reconciliation lệch | Read-only; chỉ Order workload |

### 2.1. `POST /v1/payments/initiate`

Request chuẩn chứa `order_id`, `payment_attempt_number` tăng đơn điệu theo order,
`amount { amount_minor, currency: VND }`, `user_id` và `return_url?`. `amount_minor`
là integer VND, không dùng `DECIMAL`/floating point và được truyền nguyên sang
provider theo quy tắc currency của adapter.

Response: `transaction_id`, `client_secret` (`pi_xxx_secret_xxx`),
`publishable_key` (`pk_...`), `expires_at`. Frontend dùng `client_secret` +
`publishable_key` để khởi tạo Stripe Elements hoặc Stripe Checkout SDK, không
redirect sang URL ngoài.

Stripe call: `PaymentIntent.create` nhận đúng `amount_minor`, `currency=vnd`, metadata
`order_id`, `payment_attempt_number`, `payment_transaction_id`, `user_id` và
`confirmation_method=AUTOMATIC`. Không nhân/chia amount ở Order hoặc Payment workflow;
chỉ Stripe adapter chịu trách nhiệm mapping canonical VND amount sang provider request.
`intent.getId()` (`pi_xxx`) lưu vào `provider_transaction_id`.

Order dùng một `Idempotency-Key` ổn định **chỉ cho một** `payment_attempt_number`.
Retry cùng attempt phải giữ key/fingerprint; một attempt mới chỉ được mở sau fact
terminal non-capture của attempt trước và phải dùng số attempt kế tiếp cùng key mới.
Payment lưu `INITIATING` + provider-create key/fingerprint/immutable metadata trước
khi gọi Stripe. Chỉ sau khi chứng minh được một PaymentIntent qua response, replay hay
reconciliation, Payment mới atomically ghi provider identity, encrypted `client_secret`,
chuyển `PENDING` và phát `PAYMENT_INITIATED`. `INITIATING` trả result retryable, không
được expire hay thay bằng payment identity khác.

### 2.1a. `POST /v1/payments/{transactionId}/cancel`

Request tối thiểu: `order_id`, `reason` (`PAYMENT_TIMEOUT`, `BUYER_CANCELLED` hoặc
`ORDER_CANCELLED_PRE_CAPTURE`). Header `Idempotency-Key` do Order sinh và cố định
cho intent hủy. Payment xác minh transaction thuộc order, lưu `ProviderOperation`
`CANCEL_REQUESTED` trước khi gọi `PaymentIntent.cancel` với cùng provider idempotency
key, rồi persist outcome và audit/outbox trong local transaction.

| Payment/provider fact cuối | Response semantic | Nghĩa vụ của Order |
|---|---|---|
| Pending/processing đã bị provider cancel, hoặc đã failed/expired/canceled | `CANCELED` hoặc `ALREADY_NON_CAPTURED` | Có thể hoàn tất cancel path; khi đó mới phát `ORDER_CANCELLED`. |
| Provider đã capture/success (kể cả race với cancel) | `ALREADY_SUCCEEDED` cùng `payment_transaction_id`, amount, currency và financial version | Không được final-cancel; nhận/xử lý lane hậu thanh toán hoặc financial reconciliation. |
| Create/cancel/provider outcome chưa xác định | `CANCEL_PENDING`/`503` | Replay/query cùng command ID; giữ Order ở trạng thái cancelling, không rollback như một thất bại đã xác nhận. |

Payment phát `PAYMENT_CANCELED` hoặc `PAYMENT_SUCCESS` khi fact provider được chốt.
`ORDER_CANCELLED` event chỉ còn là factual notification/reconciliation backstop; nó
không thay thế cancel command và không được dùng để suy luận refund.

### 2.2. `POST /v1/payments/webhook/stripe`

Nhận raw body + headers, không xử lý logic tại endpoint. Message dump lên
`t.payment.webhooks.raw`: key `STRIPE:` + `stripe_event_id`; value gồm
`provider=STRIPE`, `stripe_event_id`, `raw_body`, `stripe_signature`
(`t=...,v1=...`), `received_at`. Ý nghĩa nghiệp vụ của từng Stripe event và
transition tương ứng ở `architecture.md` §3.3.

### 2.3. `POST /v1/payments/{transactionId}/refund`

Request: `amount { amount_minor, currency: VND }`, `reason` (`CUSTOMER_RETURN_SELECTED_LINES` hoặc
`ADMIN_FORCE_REFUND_BEFORE_DISPATCH`), `order_id`, `refund_request_id`, and
`inventory_evidence`. Evidence is either `RETURN_SCOPE_INSPECTION_COMPLETED`
(`refund_scope_id`, selected-line counters) or
`COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH` (atomic all-line release reference).
Header `Idempotency-Key` do Order sinh, ổn định qua retry và được truyền nguyên sang
Stripe `RequestOptions.setIdempotencyKey`.

Precondition: payment `SUCCESS`; no prior refund transaction for `order_id`;
`amount <= paid_amount`; request là explicit command từ Order after the documented
Inventory evidence, not inferred from a shipping event. Payment does not treat an
inspection `FAIL` counter as reason to reject a valid Order command.

Stripe call: `Refund.create` với `payment_intent=pi_xxx`, `amount`,
`reason=REQUESTED_BY_CUSTOMER`, metadata `order_id`, `refund_request_id`.
`refund.getId()` (`re_xxx`) lưu vào `provider_refund_id`.

Response: `refund_txn_id`, `status=PROCESSING`. Stripe có thể trả `succeeded` ngay
hoặc `pending` chờ issuing bank; kết quả cuối đến qua webhook `charge.refunded`
hoặc Refund Status Poller.

## 3. Event envelope, delivery và ordering

Event phát qua transactional outbox + Debezium CDC, delivery at-least-once, topic
`t.payment.events.v2`, partition key `order_id` (thay vì `transaction_id`) để mọi
event payment + refund của cùng đơn vào cùng partition, cho Order consumer strict
ordering ([ADR-007](./decisions/ADR-007-order-id-partition-key.md)).

Envelope CloudEvents v1.0 theo AsyncAPI v2:

| Field | Ý nghĩa |
|---|---|
| CloudEvents `id` | UUID; downstream dùng để idempotency check |
| CloudEvents `type` | `PAYMENT_*`, `REFUND_*` |
| CloudEvents `subject` | `payments/{payment_transaction_id}` |
| `data.order_id` | Canonical routing/partition key cho Order consumer |
| `data.payment_transaction_id` | Financial identity bắt buộc |
| `data.schema_version` | Hằng số `2` |
| `data.financial_state_version` | Số nguyên monotonic theo payment transaction |
| `data.amount` | `{ amount_minor, currency: "VND" }` khi event mang payment amount |

Routing contract: dù `aggregate_id` là transaction-level, Order Service route
workflow theo `payload.order_id`, không theo `aggregate_id`.

Ví dụ `PAYMENT_SUCCESS` (event quan trọng nhất của hệ thống thanh toán):

```json
{
  "specversion": "1.0",
  "id": "123e4567-e89b-12d3-a456-426614174001",
  "type": "PAYMENT_SUCCESS",
  "subject": "payments/TXN-ABC123",
  "time": "2026-02-28T10:07:00Z",
  "source": "payment-service",
  "data": {
    "schema_version": 2,
    "payment_transaction_id": "TXN-ABC123",
    "order_id": "10001",
    "amount": { "amount_minor": 995000, "currency": "VND" },
    "financial_state_version": 3,
    "occurred_at": "2026-02-28T10:07:00Z"
  }
}
```

## 4. Event phát ra

Payload tối thiểu theo Phase 20 ([ADR-008](./decisions/ADR-008-minimal-event-payload-phase-20.md)):
chỉ giữ field Order cần để route workflow/commit FSM; Payment vẫn giữ đầy đủ field
ở internal log/audit.

| Event | Khi phát (FSM) | Payload | Consumer |
|---|---|---|---|
| `PAYMENT_INITIATED` | PaymentIntent đã được xác định, `INITIATING -> PENDING` | identity, `payment_attempt_number`, VND amount, `expires_at`, `financial_state_version`; **không có `client_secret`** | Analytics (funnel conversion) |
| `PAYMENT_SUCCESS` | Webhook/reconciliation confirm, `PROCESSING/PENDING -> SUCCESS` (`T2`) | identity, VND amount, `occurred_at`, `financial_state_version` | **Order** — signal financial-recovery workflow, không chỉ workflow checkout còn mở |
| `PAYMENT_CANCELED` | Cancel command/webhook confirms non-capture (`T5`) | `payment_transaction_id`, `order_id`, `reason`, `canceled_at`, `financial_state_version` | **Order** — complete pre-capture cancellation only after matching transaction fact |
| `PAYMENT_FAILED` | Webhook báo thất bại, `PROCESSING -> FAILED` (`T3`) | `payment_transaction_id`, `order_id`, `error_code` (ví dụ `INSUFFICIENT_BALANCE`), `failed_at` | Order — cancel + compensation |
| `PAYMENT_EXPIRED` | Scanner `expires_at < NOW()`, `PENDING/PROCESSING -> EXPIRED` (`T4`) | `payment_transaction_id`, `order_id`, `expired_at` | Order — cancel |
| `REFUND_INITIATED` | Order request refund, Refund `[*] -> PENDING` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `refund_amount {amount_minor,currency:"VND"}`, `reason`, `stripe_reason` (`requested_by_customer`), `initiated_at` | Analytics |
| `REFUND_PROCESSING` | Đã gọi Stripe thành công, Refund `PENDING -> PROCESSING`, Payment `SUCCESS -> REFUNDING` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `processing_at`, `financial_state_version` | Order (đánh dấu tiến trình), Analytics |
| `REFUND_SUCCESS` | Stripe xác nhận, Refund `PROCESSING -> SUCCESS`, Payment `REFUNDING -> REFUNDED` | identity, `refund_amount {amount_minor,currency:"VND"}`, `occurred_at`, `financial_state_version` | **Order** — `RETURN_REQUESTED -> REFUNDED`, phát `ORDER_REFUNDED` |
| `REFUND_FAILED` | Stripe từ chối, Refund `PROCESSING -> FAILED` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `error_code`, `failed_at`, `financial_state_version` | Order (alert), SysAdmin Dashboard |
| `REFUND_REVERSED` | Provider đảo chiều refund đã success/processing (optional, provider-dependent) | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `reason_code`, `reversed_at`, `financial_state_version` | Order (manual-review flow, `ORDER_REFUND_FAILED` escalation), SysAdmin, Accounting/Reconciliation |

`client_secret` là API-response-only, được mã hóa tại Payment và chỉ Order trả cho
frontend qua authenticated checkout response; không được đưa vào outbox, Kafka, audit
event hoặc log.

### 4.1. Các field đã chủ đích loại bỏ khỏi payload

- Phase 18: `payment_method` không còn trong payload; `provider` luôn `STRIPE`,
  `provider_transaction_id` = `pi_xxx`, `provider_refund_id` = `re_xxx`,
  `stripe_event_id` = `evt_xxx`.
- Phase 20 bỏ khỏi Kafka payload: `user_id` (Analytics tự join từ `order_id`),
  `provider` (luôn STRIPE), `payment_method_type`,
  `provider_transaction_id` (chỉ cần cho refund call nội bộ),
  `provider_charge_id`, `provider_refund_id`, `payment_event_sequence_id`,
  `refund_event_sequence_id` (Order không guard theo), `error_message` và
  `reason_message` (Notification map từ `error_code`/`reason_code` qua i18n key).
- V2 là version duy nhất trên topic `t.payment.events.v2`; không có mixed V1/V2
  topic. `payment_transaction_id`, VND money object và `financial_state_version` là
  financial proof bắt buộc, không phải provider detail.

### 4.2. Reliability rules cho financial events

1. Ordering nội bộ: producer luôn partition theo `order_id`.
2. Webhook reality: upstream có thể out-of-order; worker so sánh trạng thái hiện
   tại + transition hợp lệ trước khi update.
3. Idempotency consumer: consumer dùng `event_id` làm khóa chống trùng, lưu durable
   inbox cùng mutation local. Order còn phải validate `order_id`, mapped
   `payment_attempt_number`/`payment_transaction_id`, amount, currency và monotonic
   `financial_state_version` per transaction;
   partition order không thay thế các guard này.
4. At-least-once compatible: mọi consumer an toàn khi nhận lặp cùng event.
5. Terminal state governance: `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED`
   là terminal signal phải audit vào `payment_status_history`.

## 5. Event tiêu thụ

### 6.1. `ORDER_CANCELLED` — `payment-order-consumer` trên `t.order.events`

Payload cũ: `order_id`, `previous_status`, `reason` (ví dụ `PAYMENT_TIMEOUT`);
envelope `aggregate_type=Order`, `aggregate_id=order_id`, `correlation_id`
Workflow ID, `source=order-service`.

Xử lý theo trạng thái payment transaction:

| Điều kiện | Hành động |
|---|---|
| `previous_status != CANCELLING` hoặc transaction không khớp | Không đổi tiền; ghi reconciliation marker. `ORDER_CANCELLED` không là lệnh hủy. |
| Payment `PENDING`/`PROCESSING` | Ghi marker/backstop và kiểm tra cancel command cùng idempotency key; không tự gọi một cancel thứ hai. |
| Payment `SUCCESS` | Không auto-refund; ghi reconciliation marker để Order xử lý lane hậu thanh toán riêng |
| Payment `EXPIRED`/`CANCELLED`/`FAILED` | Ignore |

Contract: refund chỉ được khởi tạo bởi explicit refund request từ
`OrderRefundWorkflow`, không suy luận từ `ORDER_CANCELLED`
([ADR-010](./decisions/ADR-010-refund-only-by-explicit-order-command.md)).

Consumer bắt buộc insert `consumed_events(event_id PK, source, payload_hash, received_at)`
cùng transaction với reconciliation marker. Retry không ACK với lỗi kỹ thuật; sau giới
hạn chuyển nguyên record vào `t.order.events.payment.dlq` kèm attempt/error audit.

### 5.2. Stripe webhook events (qua `t.payment.webhooks.raw`)

| Stripe event | Xử lý |
|---|---|
| `payment_intent.succeeded` | `T2` → `SUCCESS`, phát `PAYMENT_SUCCESS` |
| `payment_intent.payment_failed` | `T3` → `FAILED`, phát `PAYMENT_FAILED` |
| `payment_intent.canceled` | `T5` → `CANCELED` |
| `charge.refunded` | `R2` → refund `SUCCESS`, `T7` → `REFUNDED`, phát `REFUND_SUCCESS`; `status=failed` → reversal (`T9`, `REFUND_REVERSED`) |

Consumer group cho worker này chưa được đặt tên trong thiết kế cũ. Retry: lỗi tạm
thời (lock DB, Stripe API timeout) requeue với exponential backoff; lỗi signature →
`FAILED_SIGNATURE` và Webhook Auto-Retry Worker cover case sai key/outage.

## 6. Tương thích

- API v1 và events v2 đều chỉ compatible-additive; breaking change phải tạo version/topic mới.
- Consumer phải ignore optional unknown field nhưng phải reject/park missing financial proof bắt buộc.
- CloudEvents `id` unique; downstream dùng để idempotency check.
- `data.schema_version` bắt buộc và cố định `2` trên `t.payment.events.v2`.
- Stripe webhook: Stripe retry 3–5 lần với exponential backoff, cửa sổ timestamp
  300s; Payment phải chịu được duplicate.
- Chưa có breaking-change check trong CI, chưa có provider/consumer test để
  chuyển trạng thái `verified`.
