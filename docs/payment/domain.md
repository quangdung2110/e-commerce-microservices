---
doc_id: payment-domain
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Mô hình miền nghiệp vụ

> File này gom dual FSM (Payment Transaction + Refund Transaction), guard rules,
> audit trail và invariant từ thiết kế cũ. Database field và payload chi tiết nằm
> ở `data.md` và `interfaces.md`.

## 1. Ngôn ngữ thống nhất

| Thuật ngữ | Định nghĩa | Không được nhầm với |
|---|---|---|
| Payment Transaction | Một lần người dùng cố gắng thanh toán cho một order; `id` là `txn_ref` gửi lên Stripe (ví dụ `TXN-ABC123`) | Order; một order có thể có nhiều transaction nếu thanh toán lỗi và thử lại |
| Refund Transaction | Một transaction hoàn tiền duy nhất trọn đời cho một order, gắn với một payment transaction `SUCCESS` | Order refund case do Order sở hữu |
| PaymentIntent | Đối tượng Stripe `pi_xxx` đại diện ý định thanh toán; `client_secret` dùng ở frontend | Charge/`provider_charge_id` |
| Stripe Refund | Đối tượng Stripe `re_xxx` tạo bởi `Refund.create` | Refund transaction nội bộ |
| Stripe Event | Webhook payload có `stripe_event_id` (`evt_xxx`) và `event_type` | Event nội bộ trên `t.payment.events` |
| Webhook | Callback HTTPS từ Stripe, có thể duplicate, delay, out-of-order | Polling/reconciliation |
| Stripe-Signature | Header `t=<timestamp>,v1=<signature>` được verify bằng HMAC-SHA256 với `whsec_...` | API key `sk_...` |
| Phiên thanh toán | Khoảng thời gian từ tạo transaction đến `expires_at` (15 phút) | TTL Redis idempotency (15 phút + 60s) |
| Accepted | Refund đã được nhận lệnh (`PROCESSING`), chưa phải hoàn tiền thành công | Final (`SUCCESS`/`FAILED`/`REVERSED`) |
| Reversal | Provider đảo chiều một refund đã báo success (ví dụ tài khoản đích không hợp lệ) | Refund failed sớm khi validate |
| Reconciliation | Đối soát định kỳ DB nội bộ ↔ Stripe, có quyền phát transition bù | Webhook Auto-Retry |
| Manual review | Cờ `requires_manual_review=TRUE` để CSKH/Kế toán xử lý thủ công | DLQ kỹ thuật |
| Correction transition | Transition do Reconciliation phát để sửa lệch trạng thái | Transition do webhook/API |

## 2. Đối tượng miền nghiệp vụ

`PaymentTransaction` là aggregate root cho thanh toán: `order_id`, `user_id`
(UUID canonical toàn hệ thống), `amount`, `currency` (cố định `VND`), `provider`
(cố định `STRIPE`), `provider_transaction_id` (`pi_xxx`), `status`, `error_code`/
`error_message`, `expires_at`, `version` (optimistic lock) và cờ
`requires_manual_review`. Identity là `id` do Payment sinh, đồng thời là tham
chiếu gửi lên Stripe.

`RefundTransaction` là aggregate riêng (logical module Refund) tham chiếu
`payment_transaction_id`, lifetime-unique `order_id`, `refund_request_id`, `refund_amount`, `reason`,
`provider_refund_id` (`re_xxx`), `status`, `idempotency_key` (unique). Refund và
Payment nằm cùng service để giữ transaction boundary cho unique-order refund và
`refund_amount <= paid_amount` ([ADR-002](./decisions/ADR-002-refund-inside-payment-service.md)).

`WebhookLog` là bản ghi raw webhook kèm kết quả xác thực và trạng thái xử lý;
`PaymentStatusHistory` là audit record cho mỗi transition của cả hai FSM.

Order, Inventory, Promotion thuộc bounded context khác; Payment chỉ giữ
`order_id`/`user_id` làm identifier và không lưu snapshot nghiệp vụ của chúng.
Trạng thái thật trên Stripe thuộc Stripe; Payment chỉ replicate và đối soát.

## 3. Lệnh và truy vấn

| Loại | Năng lực | Tác nhân | Aggregate/năng lực xử lý |
|---|---|---|---|
| Lệnh | Khởi tạo payment | Order (`InitiatePaymentActivity`) | PaymentTransaction (`[*] -> INITIATING -> PENDING`) |
| Lệnh | Áp webhook Stripe | Stripe qua Webhook Processor | PaymentTransaction/RefundTransaction FSM |
| Lệnh | Hết hạn phiên | Expiration Scanner | PaymentTransaction (`T4`) |
| Lệnh | Hủy payment pending | Order (`CancelPaymentActivity`) | PaymentTransaction (`T5`) sau provider fact; `ORDER_CANCELLED` chỉ đối soát |
| Lệnh | Khởi tạo refund | Order (`OrderRefundWorkflow`) | RefundTransaction (`[*] -> PENDING`), PaymentTransaction (`T6`) |
| Lệnh | Chốt refund | Webhook/Refund Status Poller/Reconciliation | RefundTransaction (`R2`/`R3`), PaymentTransaction (`T7`/`T8`/`T9`) |
| Lệnh | Correction transition | Reconciliation Worker | Cả hai FSM |
| Lệnh | Replay webhook/DLQ | Vận hành (manual) | Webhook Processor qua Kafka |
| Truy vấn | Chi tiết payment transaction kèm refunds | Client polling/Order | PostgreSQL read |
| Truy vấn | Chi tiết refund transaction | Order/CSKH | PostgreSQL read (khuyến nghị) |
| Truy vấn | Tra cứu giao dịch cũ > 6 tháng | CSKH | Tiered read DB → S3/Athena |

## 4. Quy tắc nghiệp vụ

| ID | Quy tắc | Điều kiện | Kết quả khi vi phạm |
|---|---|---|---|
| `RULE-PAY-001` | Idempotency initiate theo `order_id` | Key `payment:initiate:{order_id}` tồn tại | Kiểm tra `expires_at`; nếu transaction cũ còn hiệu lực trả lại `transaction_id` + `client_secret` cũ; nếu `EXPIRED` hoặc `expires_at <= NOW()` tạo transaction mới và ghi đè pointer |
| `RULE-PAY-002` | Phiên thanh toán 15 phút | `expires_at = created_at + 15 phút` | Scanner chuyển `EXPIRED`, phát `PAYMENT_EXPIRED` |
| `RULE-PAY-003` | Refund chỉ trên payment `SUCCESS` | `payment.status != SUCCESS` | `400 Bad Request` |
| `RULE-PAY-004` | Một refund transaction/order và amount không vượt paid amount | Refund `order_id` đã tồn tại với key khác, hoặc `new_amount > paid_amount` | `409 ORDER_REFUND_ALREADY_EXISTS` hoặc `400 Bad Request` |
| `RULE-PAY-005` | Refund bắt buộc `Idempotency-Key` | Trùng key | Trả cùng kết quả cũ, không gọi Stripe; nếu đang `PROCESSING` trả processing |
| `RULE-PAY-006` | Refund là explicit Order command sau đúng Inventory evidence | Không có selected-scope inspection completion hoặc atomic pre-dispatch full-order release evidence | Không khởi tạo refund |
| `RULE-PAY-007` | Webhook signature hợp lệ và `\|now - t\| <= 300s` | Sai chữ ký hoặc ngoài cửa sổ | `webhook_logs.status = FAILED_SIGNATURE`, không parse business payload |
| `RULE-PAY-008` | Amount/currency/`provider_transaction_id` khớp | Không map được hoặc lệch | Reject transition, ghi log |
| `RULE-PAY-009` | Webhook dedup theo `(provider, provider_transaction_id, event_type)` và `stripe_event_id` | Duplicate | `ON CONFLICT DO NOTHING`, ack thành công |
| `RULE-PAY-010` | Cancel trước capture là Payment command idempotent | Order chưa có `CANCELED`/`ALREADY_NON_CAPTURED` của đúng transaction | Order không được final-cancel hoặc nhả resource; `CANCEL_PENDING` phải recovery cùng key |
| `RULE-PAY-011` | Capture thắng race cancel không tự hoàn tiền | Payment đã `SUCCESS` | Trả/phát `ALREADY_SUCCEEDED`/`PAYMENT_SUCCESS`; ghi reconciliation marker, không auto-refund |
| `RULE-PAY-012` | Refund failed/reversed sau accept phải được chấp nhận | `charge.refunded` `status=failed`, poller/reconcile báo failed | `T8`/`T9` + `requires_manual_review=TRUE` |
| `RULE-PAY-013` | Accepted != Final | Response refund `PROCESSING` | Consumer không được coi là đã hoàn tiền |

## 5. Bất biến

| ID | Bất biến | Enforcement mục tiêu |
|---|---|---|
| `INV-PAY-001` | Một refund transaction trọn đời/order, amount không vượt tiền đã thanh toán | `refund_transactions.order_id UNIQUE` + check/update atomic trong cùng transaction |
| `INV-PAY-002` | Không transition nào bỏ qua guard signature/amount/reference; verify trước state transition | Webhook Processor guard; `WebhookVerifier` trước FSM |
| `INV-PAY-003` | Không publish event khi DB transaction chưa commit | Transactional outbox + Debezium CDC |
| `INV-PAY-004` | Với cùng `order_id`, event payment/refund giữ ordering theo partition key | `outbox_events.partition_key = order_id` |
| `INV-PAY-005` | Mọi terminal transition có audit record | Insert `payment_status_history` cùng transaction (Spring Event Listener pattern) |
| `INV-PAY-006` | Mọi transition idempotent theo `stripe_event_id` hoặc `provider_ref + event_type`; không override ngược terminal | Unique constraint `webhook_logs` + optimistic lock `version` + state precedence |
| `INV-PAY-007` | Refund chỉ từ explicit command của Order kèm `Idempotency-Key` | API contract; consumer `ORDER_CANCELLED` không gọi refund |
| `INV-PAY-008` | `provider = 'STRIPE'` và `currency = 'VND'` cho mọi transaction | `CHECK (provider = 'STRIPE')`; currency cố định |
| `INV-PAY-009` | Không tạo refund transaction thứ hai khi provider fail/reverse | `order_id UNIQUE`, `requires_manual_review=TRUE`, manual queue |

## 6. State machine

### 6.1. Payment Transaction FSM

| Status | Ý nghĩa |
|---|---|
| `INITIATING` | Đã persist provider-create operation; PaymentIntent chưa được chứng minh/bound về local transaction. |
| `PENDING` | Đã tạo giao dịch, chờ người dùng thanh toán hoặc webhook xác nhận |
| `PROCESSING` | Đang xử lý callback/trạng thái trung gian từ provider |
| `SUCCESS` | Thanh toán thành công, có thể trigger luồng fulfilment |
| `FAILED` | Thanh toán thất bại, không trừ tiền thành công |
| `EXPIRED` | Phiên thanh toán hết hạn, không còn hiệu lực |
| `CANCELED` | Giao dịch bị hủy (user/admin/system) |
| `REFUNDING` | Đã gửi yêu cầu hoàn tiền, chờ kết quả cuối |
| `REFUNDED` | Hoàn tiền hoàn tất theo tín hiệu cuối cùng từ provider/reconciliation |

| ID | Từ | Event/Trigger | Guard | Đến |
|---|---|---|---|---|
| `T0` | `[*]` | `INITIATE_REQUEST_ACCEPTED` | Stable Order idempotency key/fingerprint persisted | `INITIATING` |
| `T0.1` | `INITIATING` | `PROVIDER_INTENT_IDENTIFIED` | Response/replay/reconciliation proves exactly one immutable provider intent | `PENDING` |
| `T1` | `PENDING` | `PAYMENT_WEBHOOK_RECEIVED` | Signature hợp lệ + amount/ref match | `PROCESSING` |
| `T2` | `PROCESSING` | `PAYMENT_SUCCESS_CONFIRMED` (`payment_intent.succeeded`) | Provider xác nhận thành công | `SUCCESS` |
| `T3` | `PROCESSING` | `PAYMENT_FAILED_CONFIRMED` (`payment_intent.payment_failed`) | Provider xác nhận thất bại | `FAILED` |
| `T4` | `PENDING`/`PROCESSING` | `PAYMENT_SESSION_EXPIRED` | `NOW() >= expires_at` và provider read không success | `EXPIRED` |
| `T5` | `PENDING`/`PROCESSING` | `PAYMENT_CANCELED` (cancel command/provider webhook) | Provider confirms non-capture; same cancel key replayable | `CANCELED` |
| `T6` | `SUCCESS` | `REFUND_REQUEST_ACCEPTED` | Tổng refund hợp lệ | `REFUNDING` |
| `T7` | `REFUNDING` | `REFUND_SUCCESS_CONFIRMED` | Provider/poller/reconcile xác nhận | `REFUNDED` |
| `T8` | `REFUNDING` | `REFUND_FAILED_CONFIRMED` | Provider/poller/reconcile xác nhận refund thất bại | `FAILED` |
| `T9` | `REFUNDED` | `REFUND_REVERSED_CONFIRMED` | Provider xác nhận refund bị đảo chiều | `SUCCESS` |

`T8` và `T9` bật cờ `requires_manual_review = TRUE` trên `payment_transactions`
để CSKH/Kế toán xử lý thủ công, vì hệ thống không thể tự động rút tiền lần hai khi
gateway lỗi hoàn hoặc lỗi tài khoản đích.

`INITIATING` is never expired or canceled by assumption: retry/reconciliation must
first identify the provider intent or open manual review. `PAYMENT_SUCCESS` được mô tả là kết quả của `PROCESSING/PENDING -> SUCCESS`
khi webhook đến, nghĩa là `T1` và `T2` có thể được áp trong cùng lần xử lý một
webhook `payment_intent.succeeded`.

Thiết kế cũ mô tả Refund Status Poller chuyển payment `REFUNDING -> SUCCESS` khi
refund thất bại, khác với `T8` (`REFUNDING -> FAILED`); mâu thuẫn này được ghi ở
`README.md` §9 và chưa được chốt.

### 6.2. Xử lý `REFUND_REVERSED` (provider-dependent)

Stripe có thể gửi tín hiệu đảo chiều refund sau khi đã báo success (webhook
`charge.refunded` với `status = failed`, ví dụ tài khoản user khóa). Nguyên tắc:

1. Áp `T9` để kéo payment về `SUCCESS` (tiền lại nằm ở merchant).
2. Ghi audit transition vào `payment_status_history`.
3. Phát `REFUND_REVERSED` để Order cập nhật theo (ví dụ `ORDER_REFUND_FAILED`).
4. Đưa case vào hàng chờ xử lý thủ công (incident/manual review queue).

Nếu provider không hỗ trợ reversal callback, trạng thái tương tự được phát hiện
qua Reconciliation job.

### 6.3. Refund Transaction FSM

| Status | Ý nghĩa |
|---|---|
| `PENDING` | Đã tạo yêu cầu refund, chưa gửi hoặc vừa gửi sang provider |
| `PROCESSING` | Provider đang xử lý bất đồng bộ |
| `SUCCESS` | Hoàn tiền thành công |
| `FAILED` | Hoàn tiền thất bại |

| ID | Từ | Event/Trigger | Guard | Đến |
|---|---|---|---|---|
| `R1` | `PENDING` | `REFUND_REQUEST_SENT` | Request hợp lệ + idempotency pass | `PROCESSING` |
| `R2` | `PROCESSING` | `REFUND_SUCCESS_CONFIRMED` (`charge.refunded`) | Callback/poller/reconcile xác nhận | `SUCCESS` |
| `R3` | `PROCESSING` | `REFUND_FAILED_CONFIRMED` | Callback/poller/reconcile xác nhận | `FAILED` |
| `R4` | `PENDING` | `REFUND_REQUEST_REJECTED` | Validation/provider reject sớm | `FAILED` |

Refund lifecycle tuân thủ thực tiễn Stripe/Adyen/PayPal/Square/Amazon Pay:
Accepted != Final; finalization bất đồng bộ từ webhook hoặc poller/reconcile;
duplicate-safe với webhook retry/out-of-order; chấp nhận failure-after-accept;
reversal là provider variance được map ở Webhook Processor.

### 6.4. Guard rules bắt buộc

**Signature & Authenticity (Stripe chuẩn):** verify `Stripe-Signature` theo cơ
chế `t=<timestamp>,v1=<signature>`; tính lại HMAC-SHA256 với
`signed_payload = t + "." + raw_body` dùng `whsec_...` (Vault/Secret Manager);
reject nếu chữ ký sai hoặc `|now - t| > 300s` (chống replay); ghi
`FAILED_SIGNATURE` vào `webhook_logs`. Dùng `Webhook.constructEvent(payload,
signatureHeader, secret)` của Stripe Java SDK để đúng chuẩn.

**Amount & Reference:** `provider_transaction_id` phải map được về transaction
nội bộ; amount/currency phải khớp dữ liệu đã ghi nhận.

**Idempotency & Ordering:** mọi transition idempotent theo `event_id` hoặc
`provider_ref + event_type`; chỉ cho phép transition hợp lệ theo bảng; webhook
out-of-order xử lý bằng state precedence, không override ngược terminal hợp lệ.

**Audit Trail:** mọi transition Payment/Refund ghi `payment_status_history` với
`transaction_id`, `transaction_type`, `old_status`, `new_status`, `triggered_by`
(`WEBHOOK`, `CRONJOB`, `API`, `TEMPORAL`), `provider_response`, `created_at`.

## 7. Quy tắc phân quyền

- `POST /v1/payments/initiate` và `POST /v1/payments/{transactionId}/refund` chỉ
  được gọi bởi Order Service (Temporal Activity/Workflow) với service credential.
  Buyer không gọi trực tiếp command tài chính.
- Webhook endpoint không dùng user identity; authenticity dựa hoàn toàn vào
  `Stripe-Signature`.
- `GET /v1/payments/{transactionId}` phục vụ client polling hoặc Order verify;
  ownership check theo `user_id`/`order_id` chưa được thiết kế cũ mô tả chi tiết.
- Replay DLQ, freeze auto-correction, manual review là thao tác vận hành cần
  quyền quản trị và phải có audit.

## 8. Lỗi miền nghiệp vụ

| Error code | Điều kiện phát sinh | Retryable | Mapping ra interface |
|---|---|---|---|
| `PAYMENT_NOT_SUCCESS` | Refund trên payment không `SUCCESS` | No | HTTP `400` |
| `REFUND_AMOUNT_EXCEEDED` | `new_amount > paid_amount` | No | HTTP `400` |
| `ORDER_REFUND_ALREADY_EXISTS` | `order_id` đã có refund transaction khác stable key | No | HTTP `409` |
| `IDEMPOTENCY_KEY_MISSING` | Refund thiếu header `Idempotency-Key` | No | HTTP `400` |
| `REFUND_IN_PROGRESS` | Cùng key đang `PROCESSING` | Caller poll | Trả `PROCESSING`, không gọi Stripe |
| `FAILED_SIGNATURE` | Chữ ký sai hoặc ngoài cửa sổ 300s | No (auto-retry worker cover case sai key/outage) | `webhook_logs.status` |
| `UNKNOWN_PROVIDER_REFERENCE` | `pi_xxx` không map được transaction | No | Webhook `IGNORED`/log |
| `AMOUNT_MISMATCH` | Amount/currency webhook lệch DB | No | Reject transition, reconciliation |
| `INVALID_TRANSITION` | Transition không có trong bảng FSM | No | No-op + audit |
| `OPTIMISTIC_LOCK_CONFLICT` | `version` không khớp | Yes (requeue) | Consumer retry với backoff |
| `LOCK_NOT_ACQUIRED` | Không lấy được Redis lock | Yes | Reject → retry cycle/DLQ |
| `INSUFFICIENT_BALANCE`, `REFUND_WINDOW_EXPIRED` | Error code từ Stripe trong event payload | No | `error_code` trong `PAYMENT_FAILED`/`REFUND_FAILED` |
| `REFUND_REVERSED_BY_PROVIDER` | Stripe đảo chiều refund | No | `reason_code` trong `REFUND_REVERSED` |

Tên error code nội bộ (trừ `FAILED_SIGNATURE` và các code trong payload event)
được đặt ở lần refactor này để có identifier ổn định; thiết kế cũ chỉ mô tả điều
kiện và HTTP status. Timeout Stripe, DB unavailable, Kafka unavailable là lỗi hạ
tầng, không phải domain error.

## 9. Ví dụ nghiệp vụ

- **Thanh toán thành công:** Order gọi initiate cho order `10001`, amount
  `995000` VND → transaction `TXN-ABC123` `PENDING`, PaymentIntent `pi_xxx`. Khách
  thanh toán qua Stripe Elements; webhook `payment_intent.succeeded` → `T1`+`T2` →
  `SUCCESS`, phát `PAYMENT_SUCCESS` với `paid_at`.
- **Webhook trùng:** Stripe retry cùng `evt_xxx` ba lần. Lần đầu insert
  `webhook_logs` thành công và transition; hai lần sau `ON CONFLICT DO NOTHING`
  trả 0 rows → ack, không transition, không phát event.
- **Initiate lặp lại trong phiên còn hiệu lực:** Order retry activity với cùng
  `order_id` sau 2 phút; key Redis tồn tại, `expires_at` còn 13 phút → trả lại
  `TXN-ABC123` + `client_secret` cũ, không tạo PaymentIntent mới.
- **Initiate sau khi hết hạn:** cùng `order_id` sau 20 phút; transaction cũ
  `EXPIRED` → tạo transaction mới, ghi đè pointer.
- **Refund một phần hợp lệ:** payment `SUCCESS` 995000, đã refund 300000; request
  400000 → `300000 + 400000 <= 995000` → tạo `RFD-DEF456` `PENDING`, payment
  `REFUNDING`, gọi `Refund.create` với `Idempotency-Key` của Order → trả
  `PROCESSING`; `charge.refunded` → `R2`, `T7`, phát `REFUND_SUCCESS`.
- **Refund vượt hạn mức:** request 800000 khi đã refund 300000 → `RULE-PAY-004`
  vi phạm → `400`, không ghi refund row, không gọi Stripe.
- **Refund reversed:** sau `REFUNDED`, Stripe gửi `charge.refunded` `status=failed`
  → `T9` về `SUCCESS`, `requires_manual_review=TRUE`, phát `REFUND_REVERSED`
  `reason_code=REFUND_REVERSED_BY_PROVIDER`.
- **`ORDER_CANCELLED` với payment đã `SUCCESS`:** không refund; ghi reconciliation
  marker; Order phải đi refund lane riêng.
