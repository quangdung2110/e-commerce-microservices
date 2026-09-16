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

> Chưa có OpenAPI cho API Payment và chưa có AsyncAPI/JSON Schema cho
> `t.payment.events`. File này bảo toàn semantics và payload của thiết kế cũ
> (bao gồm Phase 18 single-provider và Phase 20 payload simplification); khi có
> machine-readable contract, contract là nguồn chuẩn cho dữ liệu trên wire.

## 1. Danh mục tương tác

| Tương tác | Hướng | Giao thức | Provider | Consumer | Trạng thái |
|---|---|---|---|---|---|
| Initiate payment | Inbound | HTTP/gRPC | Payment | Order (`InitiatePaymentActivity`) | proposed; `legacy_documented` trong catalog |
| Cancel pending payment | Inbound | HTTP/gRPC | Payment | Order (`CancelPaymentActivity`) | proposed; authoritative pre-cancel command |
| Initiate refund | Inbound | HTTP/gRPC | Payment | Order (`OrderRefundWorkflow`) | proposed; `legacy_documented` |
| Query payment status | Inbound | HTTP | Payment | Client polling, Order verify | proposed |
| Query refund status | Inbound | HTTP | Payment | Order/CSKH | khuyến nghị bổ sung (chưa có trong contract) |
| Stripe webhook | Inbound | HTTPS | Payment | Stripe | proposed |
| `t.payment.events` | Outbound | Kafka | Payment | Order, Analytics | proposed; `legacy_documented` |
| `t.order.events` | Inbound | Kafka | Order | Payment (`payment-order-consumer`) | proposed semantics |
| `t.payment.webhooks.raw` | Internal | Kafka | Payment | Payment Webhook Processor | nội bộ |
| Stripe API | Outbound | HTTPS (`stripe-java`) | Stripe | Payment | Stripe API |

## 2. HTTP API

Thiết kế cũ ghi API initiate được gọi "gRPC hoặc REST"; protocol cụ thể chưa chốt
và không có file `.proto`. Mọi command tài chính chỉ được gọi bởi Order Service.

| API | Mục đích và semantics | Idempotency/lỗi chính |
|---|---|---|
| `POST /v1/payments/initiate` | Order Temporal Activity persists/replays one create operation then binds one Stripe PaymentIntent; returns client data only after provider identity is proven | Order `Idempotency-Key` + durable `ProviderOperation` are authority; Redis SETNX is only concurrency fast-path |
| `POST /v1/payments/{transactionId}/cancel` | Order yêu cầu dừng một payment chưa capture **trước** khi Order công bố `ORDER_CANCELLED`; Payment là authority cho race cancel/capture | Header `Idempotency-Key` bắt buộc. Kết quả durable là `CANCELED`/`ALREADY_NON_CAPTURED` hoặc `ALREADY_SUCCEEDED`; timeout chỉ được retry/query với cùng key, không được coi là cancel thành công. |
| `POST /v1/payments/webhook/stripe` | Endpoint cố định nhận webhook Stripe (không còn path variable `{provider}`); trả `200` < 500ms; dump raw vào Kafka | Dedup ở worker theo `stripe_event_id` và `(provider, provider_transaction_id, event_type)` |
| `GET /v1/payments/{transactionId}` | Trả chi tiết transaction kèm danh sách refund (`LEFT JOIN refund_transactions`) | Read-only; `200` |
| `POST /v1/payments/{transactionId}/refund` | Order khởi tạo one lifetime refund sau Inventory evidence; validate `SUCCESS`, unique order và amount; gọi Stripe `Refund.create`; trả `PROCESSING` | Header `Idempotency-Key` bắt buộc; trùng key → cùng kết quả; key khác sau existing order refund → `409` |
| `GET /v1/refunds/{refundTransactionId}` | Khuyến nghị: đọc trạng thái refund độc lập với read model Payment Transaction; seam để tách Refund Service Phase 2 không đổi contract phía Order/CSKH; hỗ trợ query timeline khi reconciliation lệch | Read-only |

### 2.1. `POST /v1/payments/initiate`

Request tối thiểu: `order_id`, `amount`, `currency` (`VND`), `return_url`
(optional — chỉ cho redirect-based payment method; Stripe Elements với
`confirmation_method=automatic` không cần), `metadata` (`user_id` UUID,
`description`).

Response: `transaction_id`, `client_secret` (`pi_xxx_secret_xxx`),
`publishable_key` (`pk_...`), `expires_at`. Frontend dùng `client_secret` +
`publishable_key` để khởi tạo Stripe Elements hoặc Stripe Checkout SDK, không
redirect sang URL ngoài.

Stripe call: `PaymentIntent.create` với `amount` theo VND subunit (thiết kế cũ ghi
"Stripe zero-decimal rule: amount × 100"), `currency=vnd`, metadata `order_id`,
`user_id`, `confirmation_method=AUTOMATIC`. `intent.getId()` (`pi_xxx`) lưu vào
`provider_transaction_id`.

Order bắt buộc gửi `Idempotency-Key` ổn định xuyên suốt retry. Payment lưu một
`INITIATING` row cùng provider-create key, request fingerprint và immutable metadata
`payment_transaction_id`/`order_id` **trước** khi gọi Stripe. Payment gửi chính key
đó cho Stripe. Chỉ sau khi đã có một PaymentIntent xác định được (qua response,
replay idempotent, hoặc provider reconciliation theo immutable metadata) Payment mới
atomically ghi `provider_transaction_id`, encrypted `client_secret`, chuyển sang
`PENDING` và phát `PAYMENT_INITIATED`. Nếu chưa chứng minh được kết quả create,
API trả/replay `INITIATING`; Scanner không được expire nó như một payment chưa tạo.

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

Request: `amount`, `reason` (`CUSTOMER_RETURN_SELECTED_LINES` hoặc
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

## 3. gRPC API

Thiết kế cũ nhắc "gRPC hoặc REST" cho initiate và refund nhưng không định nghĩa
service/RPC, deadline, retry hay `.proto`. Chưa có gì để mô tả thêm; sẽ chốt ở lần
chuẩn hóa contract.

## 4. Event envelope, delivery và ordering

Event phát qua transactional outbox + Debezium CDC, delivery at-least-once, topic
`t.payment.events`, partition key `order_id` (thay vì `transaction_id`) để mọi
event payment + refund của cùng đơn vào cùng partition, cho Order consumer strict
ordering ([ADR-007](./decisions/ADR-007-order-id-partition-key.md)).

Envelope theo chuẩn CloudEvents-style của thiết kế cũ:

| Field | Ý nghĩa |
|---|---|
| `event_id` | UUID; downstream dùng để idempotency check |
| `event_type` | `PAYMENT_*`, `REFUND_*` |
| `aggregate_type` | Cố định `Payment` (kể cả REFUND_*) |
| `aggregate_id` | `payment_transaction_id` cho `PAYMENT_*`, `refund_transaction_id` cho `REFUND_*` |
| `correlation_id` | Temporal Workflow ID (ví dụ `wf-10001`) để truy vết E2E |
| `business_id` | `order_id`; canonical routing key cho Order consumer |
| `timestamp` | ISO 8601 UTC |
| `source` | Cố định `payment-service` |
| `schema_version` | INT, hiện tại `1`, bắt buộc |
| `financial_state_version` | Số nguyên monotonic theo payment transaction; Order dùng cùng `event_id` để phát hiện financial fact cũ/out-of-order. |
| `payload` | Nội dung chi tiết |

Routing contract: dù `aggregate_id` là transaction-level, Order Service route
workflow theo `payload.order_id`, không theo `aggregate_id`.

Ví dụ `PAYMENT_SUCCESS` (event quan trọng nhất của hệ thống thanh toán):

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "PAYMENT_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:07:00Z",
  "source": "payment-service",
  "schema_version": 2,
  "financial_state_version": 3,
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "amount": 995000,
    "currency": "VND",
    "paid_at": "2026-02-28T10:07:00Z"
  }
}
```

## 5. Event phát ra

Payload tối thiểu theo Phase 20 ([ADR-008](./decisions/ADR-008-minimal-event-payload-phase-20.md)):
chỉ giữ field Order cần để route workflow/commit FSM; Payment vẫn giữ đầy đủ field
ở internal log/audit.

| Event | Khi phát (FSM) | Payload | Consumer |
|---|---|---|---|
| `PAYMENT_INITIATED` | PaymentIntent đã được xác định, `INITIATING -> PENDING` | `payment_transaction_id`, `order_id`, `amount`, `currency`, `expires_at`, `financial_state_version` | Analytics (funnel conversion) |
| `PAYMENT_SUCCESS` | Webhook/reconciliation confirm, `PROCESSING/PENDING -> SUCCESS` (`T2`) | `payment_transaction_id` (idempotency), `order_id` (route), `amount`, `currency` (verify match), `paid_at`, `financial_state_version` | **Order** — signal financial-recovery workflow, không chỉ workflow checkout còn mở |
| `PAYMENT_CANCELED` | Cancel command/webhook confirms non-capture (`T5`) | `payment_transaction_id`, `order_id`, `reason`, `canceled_at`, `financial_state_version` | **Order** — complete pre-capture cancellation only after matching transaction fact |
| `PAYMENT_FAILED` | Webhook báo thất bại, `PROCESSING -> FAILED` (`T3`) | `payment_transaction_id`, `order_id`, `error_code` (ví dụ `INSUFFICIENT_BALANCE`), `failed_at` | Order — cancel + compensation |
| `PAYMENT_EXPIRED` | Scanner `expires_at < NOW()`, `PENDING/PROCESSING -> EXPIRED` (`T4`) | `payment_transaction_id`, `order_id`, `expired_at` | Order — cancel |
| `REFUND_INITIATED` | Order request refund, Refund `[*] -> PENDING` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `refund_amount`, `reason`, `stripe_reason` (`requested_by_customer`), `initiated_at` | Analytics |
| `REFUND_PROCESSING` | Đã gọi Stripe thành công, Refund `PENDING -> PROCESSING`, Payment `SUCCESS -> REFUNDING` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `processing_at`, `financial_state_version` | Order (đánh dấu tiến trình), Analytics |
| `REFUND_SUCCESS` | Stripe xác nhận, Refund `PROCESSING -> SUCCESS`, Payment `REFUNDING -> REFUNDED` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `refund_amount`, `currency`, `refunded_at`, `financial_state_version` | **Order** — `RETURN_REQUESTED -> REFUNDED`, phát `ORDER_REFUNDED` |
| `REFUND_FAILED` | Stripe từ chối, Refund `PROCESSING -> FAILED` | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `error_code`, `failed_at`, `financial_state_version` | Order (alert), SysAdmin Dashboard |
| `REFUND_REVERSED` | Provider đảo chiều refund đã success/processing (optional, provider-dependent) | `refund_transaction_id`, `payment_transaction_id`, `order_id`, `reason_code`, `reversed_at`, `financial_state_version` | Order (manual-review flow, `ORDER_REFUND_FAILED` escalation), SysAdmin, Accounting/Reconciliation |

Ví dụ giá trị: `aggregate_id` `TXN-ABC123` cho payment, `RFD-DEF456` cho refund;
`correlation_id` `wf-10001`; `order_id` `10001`; `amount` `995000`.

### 5.1. Các field đã chủ đích loại bỏ khỏi payload (Phase 18/20), ngoại lệ financial recovery

- Phase 18: `payment_method` không còn trong payload; `provider` luôn `STRIPE`,
  `provider_transaction_id` = `pi_xxx`, `provider_refund_id` = `re_xxx`,
  `stripe_event_id` = `evt_xxx`.
- Phase 20 bỏ khỏi Kafka payload: `user_id` (Analytics tự join từ `order_id`),
  `provider` (luôn STRIPE), `payment_method_type`,
  `provider_transaction_id` (chỉ cần cho refund call nội bộ),
  `provider_charge_id`, `provider_refund_id`, `payment_event_sequence_id`,
  `refund_event_sequence_id` (Order không guard theo), `error_message` và
  `reason_message` (Notification map từ `error_code`/`reason_code` qua i18n key).
- Version 2 giữ `currency`, `financial_state_version` và `payment_transaction_id`
  trên refund events. Đây là financial identity/ordering proof cho Order; không phải
  provider detail. Consumers version 1 tiếp tục ignore field mới.

### 5.2. Reliability rules cho refund events

1. Ordering nội bộ: producer luôn partition theo `order_id`.
2. Webhook reality: upstream có thể out-of-order; worker so sánh trạng thái hiện
   tại + transition hợp lệ trước khi update.
3. Idempotency consumer: consumer dùng `event_id` làm khóa chống trùng, lưu durable
   inbox cùng mutation local. Order còn phải validate `order_id`, original
   `payment_transaction_id`, amount, currency và monotonic `financial_state_version`;
   partition order không thay thế các guard này.
4. At-least-once compatible: mọi consumer an toàn khi nhận lặp cùng event.
5. Terminal state governance: `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED`
   là terminal signal phải audit vào `payment_status_history`.

## 6. Event tiêu thụ

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

Dedup: theo `event_id` (khuyến nghị `consumed_events`). Retry/DLQ policy cụ thể
cho consumer này chưa được thiết kế cũ mô tả.

### 6.2. Stripe webhook events (qua `t.payment.webhooks.raw`)

| Stripe event | Xử lý |
|---|---|
| `payment_intent.succeeded` | `T2` → `SUCCESS`, phát `PAYMENT_SUCCESS` |
| `payment_intent.payment_failed` | `T3` → `FAILED`, phát `PAYMENT_FAILED` |
| `payment_intent.canceled` | `T5` → `CANCELED` |
| `charge.refunded` | `R2` → refund `SUCCESS`, `T7` → `REFUNDED`, phát `REFUND_SUCCESS`; `status=failed` → reversal (`T9`, `REFUND_REVERSED`) |

Consumer group cho worker này chưa được đặt tên trong thiết kế cũ. Retry: lỗi tạm
thời (lock DB, Stripe API timeout) requeue với exponential backoff; lỗi signature →
`FAILED_SIGNATURE` và Webhook Auto-Retry Worker cover case sai key/outage.

## 7. Tương thích

- Chỉ thêm field mới, không xóa/rename field hiện có.
- Consumer phải ignore unknown field.
- `event_id` unique; downstream dùng để idempotency check.
- `schema_version` bắt buộc, hiện `1`; breaking change phải tạo version mới.
- Stripe webhook: Stripe retry 3–5 lần với exponential backoff, cửa sổ timestamp
  300s; Payment phải chịu được duplicate.
- Chưa có breaking-change check trong CI, chưa có provider/consumer test để
  chuyển trạng thái `verified`.
