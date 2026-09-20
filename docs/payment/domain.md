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
> audit trail và invariant. Database field và payload chi tiết nằm ở `data.md` và
> `interfaces.md`. Hai quyết định gần nhất định hình FSM:
> [ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md) (side effect
> provider durable, cancel là fact) và
> [ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)
> (decline không terminal; terminal non-capture đòi hỏi intent đã hủy).

## 1. Ngôn ngữ thống nhất

| Thuật ngữ | Định nghĩa | Không được nhầm với |
|---|---|---|
| Payment attempt | Một lần numbered attempt cho một order; retry giữ nguyên `payment_attempt_number` và idempotency key, attempt mới dùng số/key mới | Retry transport của cùng attempt; decline của buyer trong cùng attempt |
| Payment Transaction | Aggregate của đúng một Payment attempt; `id` (`TXN_<ULID>`) là metadata gửi lên Stripe | Order; một order có thể có nhiều transaction qua nhiều attempt terminal non-capture |
| Refund Transaction | Một transaction hoàn tiền duy nhất trọn đời cho một order (`RFD_<ULID>`), gắn với một payment transaction `SUCCESS` | Order refund case do Order sở hữu |
| Provider Operation | Một side effect Stripe durable (`CREATE`/`CANCEL`/`REFUND`, `POP_<ULID>`) có key idempotency bất biến, được commit trước khi gọi Stripe | Retry transport; command HTTP từ Order |
| Review Case | Bản ghi `payment_review_cases` (`PRC_<ULID>`) cho tình huống hệ thống không được tự quyết (refund failed/reversed, capture-after-cancel, drift, dispute) | Durable manual-resolution queue; cờ boolean `requires_manual_review` (chỉ là denormalized "có case mở") |
| PaymentIntent | Đối tượng Stripe `pi_xxx` đại diện phiên thanh toán; **sống qua nhiều lần decline** cho tới khi `succeeded` hoặc `canceled`; `client_secret` dùng ở frontend | Charge `ch_xxx` (được tạo khi capture) |
| Decline | Một lần Stripe từ chối payment method trên intent (`payment_intent.payment_failed`); intent quay về `requires_payment_method` | Terminal `FAILED` của Payment |
| Stripe Refund | Đối tượng Stripe `re_xxx` tạo bởi `Refund.create` | Refund transaction nội bộ |
| Stripe Event | Webhook payload có `stripe_event_id` (`evt_xxx`) và `event_type` | Business event v2 trên `t.payment.events.v2` |
| Webhook | Callback HTTPS từ Stripe, có thể duplicate, delay, out-of-order | Polling/reconciliation |
| Stripe-Signature | Header `t=<timestamp>,v1=<signature>` verify bằng HMAC-SHA256 với `whsec_...` | API key `sk_...` |
| Phiên thanh toán | Từ tạo transaction đến `expires_at` (15 phút); buyer có thể thử nhiều payment method trong phiên | TTL Redis idempotency (15 phút + 60s) |
| Client data | `client_secret` + `publishable_key` + `client_expires_at`; Payment lưu duy nhất (mã hóa), Order đọc qua `client-data` API khi cần | Event payload (không bao giờ chứa) |
| Accepted | Refund đã được Stripe nhận lệnh (`PROCESSING`), chưa phải hoàn tiền thành công | Final (`SUCCESS`/`FAILED`/`REVERSED`) |
| Reversal | Provider đảo chiều một refund đã báo `succeeded`: tiền quay về merchant, khách chưa nhận | Refund `FAILED` sớm (chưa từng success) |
| Reconciliation | Đối soát định kỳ DB ↔ Stripe, có quyền phát correction transition khi không bị freeze | Webhook Auto-Retry |
| Correction transition | Transition do Reconciliation phát (`CORR`) để sửa lệch trạng thái, luôn có audit và `cause_ref` | Transition do webhook/API |
| Financial fact | Một event có business alias `PAYMENT_*`/`REFUND_*`, wire type `com.ecommerce.payment.*.v2`, ghi full structured CloudEvent vào outbox kèm `financial_state_version` tăng | Audit-only transition (`T3a`) |

## 2. Đối tượng miền nghiệp vụ

`PaymentTransaction` là aggregate root cho thanh toán: `order_id`,
`payment_attempt_number`, `user_id`, `amount_minor`/`currency` (`VND`, zero-decimal
với Stripe), `provider` (`STRIPE`), `provider_transaction_id` (`pi_xxx`),
`provider_charge_id` (`ch_xxx`), client data mã hóa, `status`, `last_error_code`/
`last_error_message`, `decline_count`, `expires_at`, `succeeded_at`/`terminal_at`,
`financial_state_version`, `version` (optimistic lock) và cờ `requires_manual_review`.
Identity là `id` do Payment sinh, đồng thời là metadata gửi lên Stripe để
reconciliation bind ngược.

`ProviderOperation` là entity con của PaymentTransaction (hoặc của RefundTransaction
với `REFUND`): `operation_type`, `provider_idempotency_key` bất biến,
`request_fingerprint` canonical, `status` (`REQUESTED` → `PROVIDER_CONFIRMED`/
`PROVIDER_REJECTED`/`UNKNOWN`/`MANUAL_REVIEW_REQUIRED`), `provider_object_id`,
recovery schedule. Không có side effect Stripe nào xảy ra mà không có một
ProviderOperation đã commit trước.

`RefundTransaction` là aggregate riêng (logical module Refund) tham chiếu
`payment_transaction_id`, lifetime-unique `order_id`, `refund_request_id`,
`command_idempotency_key`, `refund_amount_minor`, `reason`, `inventory_evidence`,
`provider_refund_id` (`re_xxx`), `status`, `provider_failure_reason`. Refund và
Payment nằm cùng service để giữ transaction boundary cho unique-order refund và
`refund_amount_minor <= amount_minor` ([ADR-002](./decisions/ADR-002-refund-inside-payment-service.md)).

`ReviewCase` là aggregate vận hành: `reason_code`, `case_status` (`OPEN` →
`IN_PROGRESS` → `RESOLVED`/`ESCALATED`), `evidence` bất biến, `resolution_type`,
`resolved_by`. Một payment chỉ có một case mở tại một thời điểm.

`WebhookLog` là bản ghi raw webhook kèm kết quả xác thực và trạng thái xử lý;
`PaymentStatusHistory` là audit record cho mỗi transition của cả hai FSM (kể cả
transition audit-only và correction).

Order, Inventory, Promotion thuộc bounded context khác; Payment chỉ giữ
`order_id`/`user_id` làm identifier và không lưu snapshot nghiệp vụ của chúng. Trạng
thái thật trên Stripe thuộc Stripe; Payment replicate, đối soát và mở review case
khi lệch.

## 3. Lệnh và truy vấn

| Loại | Năng lực | Tác nhân | Aggregate/năng lực xử lý |
|---|---|---|---|
| Lệnh | Khởi tạo payment | Order (`InitiatePaymentActivity`) | PaymentTransaction (`T0` → `INITIATING`, `T0.1` → `PENDING`) + ProviderOperation `CREATE` |
| Lệnh | Hủy payment trước capture | Order (`CancelPaymentActivity`) | ProviderOperation `CANCEL` → `T5` `CANCELED` hoặc `T2` `SUCCESS` (capture thắng race) |
| Lệnh | Khởi tạo refund | Order (`OrderRefundWorkflow`) | RefundTransaction (`[*]` → `PENDING`), PaymentTransaction `T6`, ProviderOperation `REFUND` |
| Lệnh | Áp webhook Stripe | Stripe qua Webhook Processor | Cả hai FSM |
| Lệnh | Ghi nhận decline / tự chấm dứt attempt | Webhook Processor (policy decline) | `T3a` audit; hoặc ProviderOperation `CANCEL` → `T3` `FAILED` |
| Lệnh | Hết hạn phiên | Expiration Scanner | ProviderOperation `CANCEL` (`SESSION_EXPIRED`) → `T4` `EXPIRED` |
| Lệnh | Chốt refund | Webhook / Refund Status Poller / Reconciliation | RefundTransaction `R2`/`R3`/`R5`, PaymentTransaction `T7`/`T8`/`T9` |
| Lệnh | Recover provider operation | Recovery Worker | ProviderOperation replay cùng key; bind identity hoặc mở ReviewCase |
| Lệnh | Correction transition | Reconciliation Worker (khi không freeze) | Cả hai FSM, `CORR` |
| Lệnh | Mở / xử lý / đóng review case | Hệ thống mở; CSKH/Kế toán xử lý | ReviewCase |
| Lệnh | Replay durable webhook log | Vận hành (manual) | Webhook Processor qua Kafka; không có raw-webhook Kafka DLQ |
| Truy vấn | Chi tiết payment transaction kèm refund | Order verify | PostgreSQL read |
| Truy vấn | Client data cho attempt còn dùng được | Order (relay cho buyer / Checkout recovery) | PostgreSQL read + giải mã có điều kiện |
| Truy vấn | Chi tiết refund transaction | Order verify | PostgreSQL read |
| Truy vấn | Review queue, reconciliation run | Vận hành | PostgreSQL read |

## 4. Quy tắc nghiệp vụ

| ID | Quy tắc | Điều kiện | Kết quả khi vi phạm |
|---|---|---|---|
| `RULE-PAY-001` | Idempotency initiate theo `(order_id, payment_attempt_number)` và `Idempotency-Key` | Cùng key + fingerprint | Replay đúng transaction/result của attempt; cùng key khác fingerprint → `422 IDEMPOTENCY_KEY_REUSED`; attempt mới chỉ được Order tạo sau fact terminal non-capture và phải tăng số attempt/key mới |
| `RULE-PAY-002` | Phiên thanh toán 15 phút | `expires_at = created_at + payment_session_ttl` | Scanner hủy intent rồi chuyển `EXPIRED`, phát `PAYMENT_EXPIRED` |
| `RULE-PAY-003` | Refund chỉ trên payment `SUCCESS` | `payment.status != SUCCESS` | `400 PAYMENT_NOT_SUCCESS` |
| `RULE-PAY-004` | Một refund transaction/order và amount không vượt paid amount | Refund `order_id` đã tồn tại với key khác, hoặc `amount > amount_minor` | `409 ORDER_REFUND_ALREADY_EXISTS` hoặc `400 REFUND_AMOUNT_EXCEEDED` |
| `RULE-PAY-005` | Refund bắt buộc `Idempotency-Key` | Trùng key | Trả cùng kết quả cũ, không gọi Stripe; đang `PROCESSING` trả processing |
| `RULE-PAY-006` | Refund là explicit Order command sau đúng Inventory evidence; evidence được lưu lại | `CUSTOMER_RETURN_SELECTED_LINES` thiếu `RETURN_SCOPE_INSPECTION_COMPLETED {refund_scope_id, expected, received, passed, failed}`, hoặc `ADMIN_FORCE_REFUND_BEFORE_DISPATCH` thiếu `COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH {release_reference}` | `400 VALIDATION_FAILED`; không khởi tạo refund |
| `RULE-PAY-007` | Webhook signature hợp lệ và `\|now - t\| <= 300s` | Sai chữ ký hoặc ngoài cửa sổ | `webhook_logs.status = FAILED_SIGNATURE`, không parse business payload, `stripe_event_id` để trống |
| `RULE-PAY-008` | Amount/currency/`provider_transaction_id` khớp | Không map được hoặc lệch | Reject transition, `IGNORED`/`AMOUNT_MISMATCH`, mở ReviewCase khi lệch amount |
| `RULE-PAY-009` | Webhook dedup **chỉ** theo `stripe_event_id` | Duplicate `evt_xxx` | `ON CONFLICT DO NOTHING`, ack thành công. Không dedup theo `(pi, event_type)` vì Stripe hợp lệ bắn nhiều `payment_failed` trên cùng intent |
| `RULE-PAY-010` | Cancel trước capture là Payment command idempotent | Order chưa có `CANCELED`/`ALREADY_NON_CAPTURED` của đúng transaction | Order không được final-cancel hoặc nhả resource; `CANCEL_PENDING` phải recovery cùng key |
| `RULE-PAY-011` | Capture thắng race cancel không tự hoàn tiền | Payment đã `SUCCESS` | Trả/phát `ALREADY_SUCCEEDED`/`PAYMENT_SUCCESS`; mở ReviewCase `CAPTURE_AFTER_CANCEL`, không auto-refund |
| `RULE-PAY-012` | Refund failed/reversed sau accept phải được chấp nhận | Stripe refund `status=failed` (webhook `charge.refund.updated`/`refund.failed`, poller, reconcile) | Refund `PROCESSING` → `R3`/`T8`; refund `SUCCESS` → `R5`/`T9`; mở ReviewCase; không tạo refund thứ hai |
| `RULE-PAY-013` | Accepted != Final | Response refund `PROCESSING` | Consumer không được coi là đã hoàn tiền |
| `RULE-PAY-014` | Decline là phi terminal; Payment tự chấm dứt attempt theo policy | `decline_code` non-retryable, Radar `blocked`, hoặc `decline_count > max_declines_per_attempt` | Không thỏa: `T3a`, giữ `PENDING`. Thỏa: ProviderOperation `CANCEL` → intent canceled → `T3` `FAILED` + `PAYMENT_FAILED` ([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)) |
| `RULE-PAY-015` | Client data chỉ trả khi còn dùng được | `status != PENDING` hoặc `now >= client_expires_at` | `usable = false`, `client_secret = null`; ciphertext bị xóa khi rời `PENDING` |
| `RULE-PAY-016` | Correction chỉ khi không freeze | `reconciliation_runs.auto_correction_frozen = TRUE` | Drift chỉ mở ReviewCase `RECONCILIATION_DRIFT`, không phát `CORR` |

**Policy decline (`RULE-PAY-014`).** Non-retryable: Stripe `decline_code` ∈
{`fraudulent`, `lost_card`, `stolen_card`, `pickup_card`, `restricted_card`,
`security_violation`, `merchant_blacklist`} hoặc `outcome.type = blocked` (Radar).
Retryable (giữ `PENDING`): `insufficient_funds`, `generic_decline`, `expired_card`,
`incorrect_cvc`, `incorrect_number`, `processing_error`, `authentication_required`,
`do_not_honor`, `try_again_later` và mọi code khác. `max_declines_per_attempt`
mặc định 5, cấu hình ở `operations.md`.

## 5. Bất biến

| ID | Bất biến | Enforcement mục tiêu |
|---|---|---|
| `INV-PAY-001` | Một refund transaction trọn đời/order, amount không vượt tiền đã thanh toán | `refund_transactions.order_id UNIQUE` + check atomic trong cùng transaction |
| `INV-PAY-002` | Không transition nào bỏ qua guard signature/amount/reference; verify trước state transition | `WebhookVerifier` trước FSM; `stripe_event_id` chỉ set sau verify |
| `INV-PAY-003` | Không publish event khi DB transaction chưa commit | Transactional outbox + Debezium CDC |
| `INV-PAY-004` | Với cùng `order_id`, event payment/refund giữ ordering theo partition key | `outbox_events.partition_key = order_id` |
| `INV-PAY-005` | Mọi transition (financial, audit-only, correction) có audit record | `INSERT payment_status_history` cùng transaction, append-only |
| `INV-PAY-006` | Mọi transition idempotent theo `stripe_event_id`; không override ngược terminal | `webhook_logs UNIQUE (stripe_event_id)` + optimistic lock `version` + state precedence theo `event_created_at` |
| `INV-PAY-007` | Refund chỉ từ explicit command của Order kèm `Idempotency-Key` và Inventory evidence | API contract; `inventory_evidence NOT NULL`; Payment không suy luận refund từ event |
| `INV-PAY-008` | `provider = 'STRIPE'`, VND integer, `currency = 'VND'` | `CHECK` ở DB; adapter không nhân/chia amount |
| `INV-PAY-009` | Không tạo refund transaction thứ hai khi provider fail/reverse | `order_id UNIQUE`; ReviewCase; resolution `MANUAL_REFUND_OUTSIDE_SYSTEM` |
| `INV-PAY-010` | Không payment nào vào `FAILED`/`EXPIRED`/`CANCELED` khi PaymentIntent còn capture được | Mọi `T3`/`T4`/`T5` đi qua ProviderOperation `CANCEL` `PROVIDER_CONFIRMED` hoặc webhook `payment_intent.canceled` đã verify; cancel trả `succeeded` → `T2` |
| `INV-PAY-011` | Không side effect Stripe nào thiếu ProviderOperation đã commit với key bất biến | Commit operation trước call; `provider_idempotency_key UNIQUE`; partial unique một `CREATE`/một `CANCEL` per payment |
| `INV-PAY-012` | `financial_state_version` tăng đúng 1 cho mỗi financial fact của payment, không giảm, không nhảy | Tăng trong cùng `UPDATE` với insert outbox; history ghi giá trị sau |
| `INV-PAY-013` | `client_secret` không rời Payment ngoài response initiate/client-data cho Order workload | Mã hóa at rest; cấm trong outbox/log/idempotency response; xóa khi rời `PENDING` |

## 6. State machine

### 6.1. Payment Transaction FSM

| Status | Ý nghĩa |
|---|---|
| `INITIATING` | Đã persist ProviderOperation `CREATE`; PaymentIntent chưa được chứng minh/bound |
| `PENDING` | Có `pi_xxx`; chờ buyer thanh toán. Ở lại `PENDING` qua các lần decline retryable |
| `PROCESSING` | Stripe báo `processing` (payment method bất đồng bộ); không cancel được, chờ `succeeded`/`payment_failed` |
| `SUCCESS` | Đã capture; có `ch_xxx`; nguồn cho refund |
| `FAILED` | Payment chấm dứt attempt sau decline non-retryable/quá ngưỡng **và** intent đã canceled |
| `EXPIRED` | Hết phiên **và** intent đã canceled |
| `CANCELED` | Order command cancel, hoặc Stripe/Dashboard hủy intent; intent đã canceled |
| `REFUNDING` | Đã có refund transaction, chờ kết quả cuối |
| `REFUNDED` | Refund `SUCCESS` |

Terminal non-capture: `FAILED`, `EXPIRED`, `CANCELED` — cả ba đều là fact "intent
không thể capture" (`INV-PAY-010`) và cho phép Order mở attempt kế tiếp.

| ID | Từ | Event/Trigger | Guard | Đến | Financial fact |
|---|---|---|---|---|---|
| `T0` | `[*]` | `INITIATE_REQUEST_ACCEPTED` | `payment_idempotency` + ProviderOperation `CREATE` committed | `INITIATING` | — |
| `T0.1` | `INITIATING` | `PROVIDER_INTENT_IDENTIFIED` | Response/replay/reconciliation chứng minh đúng một `pi_xxx`; client data mã hóa được ghi | `PENDING` | `PAYMENT_INITIATED` |
| `T1` | `PENDING` | `PROVIDER_PROCESSING` (`payment_intent.processing`) | Signature + reference match | `PROCESSING` | — |
| `T2` | `PENDING`/`PROCESSING` | `PAYMENT_SUCCESS_CONFIRMED` (`payment_intent.succeeded`, cancel trả `succeeded`, reconciliation) | Amount/currency/reference match; ghi `ch_xxx`, `succeeded_at`; xóa client secret | `SUCCESS` | `PAYMENT_SUCCESS` |
| `T3a` | `PENDING`/`PROCESSING` | `PAYMENT_DECLINED` (`payment_intent.payment_failed`) | Decline retryable và `decline_count < max` | `PENDING` | — (audit-only; ghi `last_error_*`, `decline_count++`) |
| `T3` | `PENDING` | `PAYMENT_FAILED_CONFIRMED` | Decline non-retryable hoặc quá ngưỡng **và** ProviderOperation `CANCEL(DECLINED_*)` `PROVIDER_CONFIRMED` (hoặc webhook `payment_intent.canceled` khớp operation) | `FAILED` | `PAYMENT_FAILED` |
| `T4` | `PENDING` | `PAYMENT_SESSION_EXPIRED` | `now() >= expires_at` **và** ProviderOperation `CANCEL(SESSION_EXPIRED)` `PROVIDER_CONFIRMED` | `EXPIRED` | `PAYMENT_EXPIRED` |
| `T5` | `PENDING` | `PAYMENT_CANCELED` | Order cancel command với ProviderOperation `CANCEL(ORDER_COMMAND:*)` `PROVIDER_CONFIRMED`, hoặc webhook `payment_intent.canceled` không khớp operation nào | `CANCELED` | `PAYMENT_CANCELED` |
| `T6` | `SUCCESS` | `REFUND_REQUEST_ACCEPTED` | `RULE-PAY-003/004/006`; insert refund `PENDING` cùng transaction | `REFUNDING` | `REFUND_INITIATED` |
| `T7` | `REFUNDING` | `REFUND_SUCCESS_CONFIRMED` | Cùng transaction với `R2` | `REFUNDED` | `REFUND_SUCCESS` |
| `T8` | `REFUNDING` | `REFUND_FAILED_CONFIRMED` | Cùng transaction với `R3`/`R4`; mở ReviewCase `REFUND_FAILED_AFTER_ACCEPT` | `SUCCESS` | `REFUND_FAILED` |
| `T9` | `REFUNDED` | `REFUND_REVERSED_CONFIRMED` | Cùng transaction với `R5`; mở ReviewCase `REFUND_REVERSED` | `SUCCESS` | `REFUND_REVERSED` |
| `CORR` | bất kỳ | `RECONCILIATION_CORRECTION` | Reconciliation không freeze; provider fact được đọc trực tiếp; transition đích hợp lệ theo bảng này | theo fact | Event tương ứng với transition đích |

Ghi chú:

- `PROCESSING` không có đường ra `EXPIRED`/`CANCELED` trực tiếp: Stripe không cho
  cancel intent đang `processing`. Scanner/cancel command gặp `PROCESSING` trả
  `CANCEL_PENDING` và chờ `succeeded` (→ `T2`) hoặc `payment_failed` (→ `T3a` về
  `PENDING`, sau đó `T4`/`T5` có thể áp).
- `INITIATING` không bao giờ bị expire/cancel bằng suy luận: recovery phải bind
  intent hoặc mở ReviewCase `PROVIDER_IDENTITY_UNBOUND`.
- `T8` quay về `SUCCESS` vì tiền gốc vẫn đã capture khi refund không thành công;
  `T9` cũng về `SUCCESS` vì tiền đã quay lại merchant. Cả hai bật
  `requires_manual_review` qua ReviewCase.
- Webhook `payment_intent.succeeded` có thể đến khi payment còn `PENDING` (không
  qua `PROCESSING`) — `T2` nhận cả hai nguồn.

### 6.2. Xử lý reversal (`R5`/`T9`)

Refund với Stripe là bất đồng bộ hai lớp: Stripe trừ số dư merchant và báo
`succeeded`, rồi gửi lệnh về ngân hàng phát hành. Bước sau có thể thất bại sau vài
ngày (thẻ khóa/hết hạn, tài khoản đóng): Stripe **trả tiền về merchant** và bắn
webhook `charge.refund.updated` (hoặc `refund.failed`) với `status = failed` cho
refund đã từng `succeeded`. Nguyên tắc:

1. Refund `SUCCESS` → `R5` `REVERSED`, payment `REFUNDED` → `T9` `SUCCESS`, cùng
   transaction.
2. Ghi `provider_failure_reason`, `reversed_at`; audit hai transition.
3. Mở ReviewCase `REFUND_REVERSED`; phát `REFUND_REVERSED` để Order về manual-review.
4. Hệ thống **không** tạo refund thứ hai (`INV-PAY-009`). CSKH/Kế toán hoàn thủ
   công ngoài hệ thống (chuyển khoản/voucher) và đóng case với
   `MANUAL_REFUND_OUTSIDE_SYSTEM`.

Nếu webhook miss, Refund Status Poller không phát hiện được (poller chỉ quét
`PROCESSING`); Reconciliation so `Refund.retrieve` với refund `SUCCESS` trong cửa sổ
và phát `CORR` tương đương `R5`/`T9`.

### 6.3. Refund Transaction FSM

| Status | Ý nghĩa |
|---|---|
| `PENDING` | Đã tạo yêu cầu refund, ProviderOperation `REFUND` đã commit, chưa/đang gửi sang Stripe |
| `PROCESSING` | Stripe đã nhận lệnh (`re_xxx`), đang xử lý bất đồng bộ (Accepted) |
| `SUCCESS` | Stripe xác nhận `succeeded` |
| `FAILED` | Thất bại trước khi từng `succeeded` (reject sớm hoặc fail sau accept) |
| `REVERSED` | Đã từng `SUCCESS`, provider đảo chiều; tiền về merchant, khách chưa nhận |

| ID | Từ | Event/Trigger | Guard | Đến | Financial fact |
|---|---|---|---|---|---|
| `R1` | `PENDING` | `REFUND_REQUEST_SENT` | `Refund.create` trả `re_xxx` (`pending` hoặc `succeeded`) | `PROCESSING` | `REFUND_PROCESSING` |
| `R2` | `PROCESSING` | `REFUND_SUCCESS_CONFIRMED` (`charge.refunded` / `Refund.retrieve` `succeeded`) | Webhook/poller/reconcile | `SUCCESS` | `REFUND_SUCCESS` (cùng `T7`) |
| `R3` | `PROCESSING` | `REFUND_FAILED_CONFIRMED` (`charge.refund.updated` `status=failed`) | Webhook/poller/reconcile | `FAILED` | `REFUND_FAILED` (cùng `T8`) |
| `R4` | `PENDING` | `REFUND_REQUEST_REJECTED` | `Refund.create` bị Stripe reject (`PROVIDER_REJECTED`) | `FAILED` | `REFUND_FAILED` (cùng `T8`) |
| `R5` | `SUCCESS` | `REFUND_REVERSED_CONFIRMED` (`charge.refund.updated` `status=failed` sau success) | Webhook/reconcile | `REVERSED` | `REFUND_REVERSED` (cùng `T9`) |

Stripe trả `succeeded` ngay trong response `Refund.create` với hầu hết thẻ: Payment
vẫn đi `R1` rồi `R2` trong cùng lần xử lý (hai audit record, một financial fact
`REFUND_PROCESSING` + một `REFUND_SUCCESS`) để consumer luôn thấy Accepted trước Final.

### 6.4. Guard rules bắt buộc

**Signature & Authenticity:** verify `Stripe-Signature` (`t=<timestamp>,v1=<signature>`),
HMAC-SHA256 trên `t + "." + raw_body` với `whsec_...`; reject nếu sai hoặc
`|now - t| > 300s`; ghi `FAILED_SIGNATURE`, giữ `raw_body` nguyên byte để
Auto-Retry re-verify sau khi đổi secret. Dùng `Webhook.constructEvent` của SDK.

**Amount & Reference:** `data.object` phải map về transaction nội bộ qua
`pi_xxx`/`ch_xxx`/`re_xxx` hoặc metadata `payment_transaction_id`; amount/currency phải
khớp. Không map được → `IGNORED`; lệch amount → ReviewCase `AMOUNT_MISMATCH`.

**Idempotency & Ordering:** dedup theo `stripe_event_id`; transition hợp lệ theo bảng;
out-of-order xử lý bằng state precedence với `event_created_at`; không override ngược
terminal hợp lệ; `succeeded` đến sau `canceled` cho cùng intent → ReviewCase
`CAPTURE_AFTER_CANCEL`.

**Provider side effect:** không gọi Stripe khi chưa có ProviderOperation committed;
timeout → `UNKNOWN` → replay cùng key → reconciliation → ReviewCase; không bao giờ suy
luận non-capture.

**Audit Trail:** mọi transition ghi `payment_status_history` với `transition_id`,
`old_status`, `new_status`, `financial_state_version`, `triggered_by` (`API`,
`WEBHOOK`, `SCHEDULER`, `RECONCILIATION`, `MANUAL`), `cause_ref` (`evt_xxx`,
operation id, command key, case id, run id), `actor` khi `MANUAL`.

## 7. Quy tắc phân quyền

- `POST /v1/payments/initiate`, `POST /v1/payments/{id}/cancel`,
  `POST /v1/payments/{id}/refund` chỉ được gọi bởi Order Service (Temporal
  Activity/Workflow) với service credential. Buyer không gọi trực tiếp command tài chính.
- `GET /v1/payments/{id}`, `GET /v1/payments/{id}/client-data`,
  `GET /v1/refunds/{id}` chỉ phục vụ Order workload; Order tự authorize buyer trước
  khi relay client data. Payment không xử lý user identity.
- Webhook endpoint không dùng user identity; authenticity dựa hoàn toàn vào
  `Stripe-Signature`.
- Replay webhook log, freeze/unfreeze auto-correction, correction thủ công, xử lý/đóng review
  case là thao tác vận hành cần quyền quản trị, ghi `actor` và `cause_ref`.

## 8. Lỗi miền nghiệp vụ

| Error code | Điều kiện phát sinh | Retryable | Mapping ra interface |
|---|---|---|---|
| `PAYMENT_NOT_SUCCESS` | Refund trên payment không `SUCCESS` | No | HTTP `400` |
| `REFUND_AMOUNT_EXCEEDED` | `amount > amount_minor` | No | HTTP `400` |
| `ORDER_REFUND_ALREADY_EXISTS` | `order_id` đã có refund transaction khác key | No | HTTP `409` |
| `IDEMPOTENCY_KEY_MISSING` | Command thiếu header `Idempotency-Key` | No | HTTP `400` |
| `IDEMPOTENCY_KEY_REUSED` | Cùng `(action, key)`, command-envelope fingerprint khác | No | HTTP `422` |
| `REQUEST_IN_PROGRESS` | Cùng `(action, key)` đang có request khác giữ execution lock | Yes (poll) | HTTP `409` |
| `REFUND_IN_PROGRESS` | Cùng key refund đang `PROCESSING` | Caller poll | Trả `PROCESSING`, không gọi Stripe |
| `PAYMENT_NOT_CANCELABLE` | Cancel khi `PROCESSING` (Stripe không cho cancel) | Yes | `CANCEL_PENDING`/`503` |
| `CLIENT_DATA_NOT_USABLE` | `status != PENDING` hoặc quá `client_expires_at` | No | `usable=false`, `client_secret=null` (HTTP `200`) |
| `FAILED_SIGNATURE` | Chữ ký sai hoặc ngoài cửa sổ 300s | Auto-Retry cover đổi key/outage | `webhook_logs.status` |
| `UNKNOWN_PROVIDER_REFERENCE` | Object không map được transaction | No | Webhook `IGNORED` |
| `AMOUNT_MISMATCH` | Amount/currency webhook lệch DB | No | Reject transition; ReviewCase |
| `INVALID_TRANSITION` | Transition không có trong bảng FSM | No | No-op + audit |
| `OPTIMISTIC_LOCK_CONFLICT` | `version` không khớp | Yes (requeue) | `FAILED_PROCESSING` → retry |
| `LOCK_NOT_ACQUIRED` | Không lấy được Redis lock | Yes | Reject → Kafka redelivery; khi đã có webhook log thì Auto-Retry/review case |
| `PROVIDER_OUTCOME_UNKNOWN` | Timeout/ambiguous sau khi đã commit operation | Yes (replay cùng key) | HTTP `503 retryable=true`; operation `UNKNOWN` |
| `PROVIDER_IDENTITY_UNBOUND` | Replay + reconciliation không bind được | No | ReviewCase |
| `DECLINED_RETRYABLE` | Stripe decline thuộc tập retryable | Buyer retry | `T3a`, không phát event |
| Stripe decline code chuẩn hóa (`INSUFFICIENT_FUNDS`, `FRAUDULENT`, `STOLEN_CARD`, …) | Từ `last_payment_error.decline_code` khi `T3` | No | `error_code` trong `PAYMENT_FAILED` |
| `DECLINE_LIMIT_EXCEEDED` | `decline_count > max_declines_per_attempt` | No | `error_code` trong `PAYMENT_FAILED` |
| `REFUND_REVERSED_BY_PROVIDER` | Stripe đảo chiều refund | No | `reason_code` trong `REFUND_REVERSED` |
| `PROVIDER_CANCELED` | Intent bị hủy phía Stripe/Dashboard, không có `CANCEL` operation nội bộ | No | `reason_code` trong `PAYMENT_CANCELED` (các giá trị khác là `reason` của Order command) |
| `ORDER_MISMATCH` | `order_id` trong body không khớp transaction | No | HTTP `400` |
| `ATTEMPT_ALREADY_EXISTS` | `(order_id, payment_attempt_number)` đã có với key khác | No | HTTP `409` |
| `PAYMENT_NOT_FOUND`, `REFUND_NOT_FOUND` | ID không tồn tại | No | HTTP `404` |
| Stripe `failure_reason` chuẩn hóa (`EXPIRED_OR_CANCELED_CARD`, `LOST_OR_STOLEN_CARD`, …) | Refund failed | No | `error_code` trong `REFUND_FAILED` |

Timeout Stripe, DB unavailable, Kafka unavailable là lỗi hạ tầng, không phải domain
error; chúng luôn để lại ProviderOperation/webhook log có thể recover.

## 9. Ví dụ nghiệp vụ

- **Thanh toán thành công:** Order initiate cho order `10001`, amount `995000` VND →
  `TXN_01J...` `INITIATING` → `pi_xxx` bound → `PENDING`, `PAYMENT_INITIATED`
  (`financial_state_version=1`). Buyer thanh toán qua Elements; `payment_intent.succeeded`
  → `T2` `SUCCESS`, `PAYMENT_SUCCESS` (`version=2`, `occurred_at` = `succeeded_at`).
- **Thẻ bị từ chối rồi thử lại:** `payment_failed` `insufficient_funds` → `T3a`,
  `decline_count=1`, vẫn `PENDING`, không có event; buyer nhập thẻ khác trên cùng
  intent → `succeeded` → `T2`. Order không thấy gì ngoài `PAYMENT_SUCCESS`.
- **Thẻ bị báo mất:** `payment_failed` `stolen_card` → non-retryable → ProviderOperation
  `CANCEL(DECLINED_NON_RETRYABLE)` → `PaymentIntent.cancel` → `T3` `FAILED`,
  `PAYMENT_FAILED error_code=STOLEN_CARD`. Order mở attempt 2 với `pi_yyy`; `pi_xxx`
  đã chết nên không thể capture muộn.
- **Hết phiên:** 15 phút không thanh toán → Scanner persist `CANCEL(SESSION_EXPIRED)`
  → cancel → `T4` `EXPIRED`, `PAYMENT_EXPIRED`. Nếu cancel trả `succeeded` (buyer bấm
  xác nhận đúng lúc) → `T2` `SUCCESS` thay vì expire.
- **Webhook trùng:** Stripe retry cùng `evt_xxx` ba lần → lần đầu insert + transition;
  hai lần sau `ON CONFLICT DO NOTHING` → ack, `DUPLICATE`, không transition.
- **Initiate replay trong phiên:** Order retry cùng key sau 2 phút → `payment_idempotency`
  `COMPLETED` → giải mã lại `client_secret` từ `TXN_01J...` (còn `PENDING`, chưa quá
  `client_expires_at`) → cùng response, không tạo PaymentIntent mới.
- **Buyer reload trang thanh toán:** Order gọi `GET /v1/payments/TXN_01J.../client-data`
  → `usable=true` + secret; sau `SUCCESS` gọi lại → `usable=false`, `client_secret=null`.
- **Refund hợp lệ:** payment `SUCCESS` 995000; Order gửi refund 400000 với
  `refund_request_id`, evidence inspection → `RFD_01J...` `PENDING`, `T6` `REFUNDING`,
  `REFUND_INITIATED`; `Refund.create` trả `succeeded` → `R1` `PROCESSING`
  (`REFUND_PROCESSING`) rồi `R2` `SUCCESS` + `T7` `REFUNDED` (`REFUND_SUCCESS`).
- **Refund thứ hai:** Order gửi key khác cho cùng order → `409 ORDER_REFUND_ALREADY_EXISTS`,
  không ghi row, không gọi Stripe.
- **Refund reversed:** 3 ngày sau `REFUNDED`, `charge.refund.updated status=failed`
  `failure_reason=expired_or_canceled_card` → `R5` `REVERSED` + `T9` `SUCCESS`,
  ReviewCase `REFUND_REVERSED`, `REFUND_REVERSED reason_code=REFUND_REVERSED_BY_PROVIDER`.
  Kế toán chuyển khoản tay, đóng case `MANUAL_REFUND_OUTSIDE_SYSTEM`.
- **Cancel sau capture:** Order cancel khi buyer vừa thanh toán → `PaymentIntent.cancel`
  trả `succeeded` → `T2` `SUCCESS`, response `ALREADY_SUCCEEDED`, ReviewCase
  `CAPTURE_AFTER_CANCEL`; Order đi lane hậu thanh toán, không auto-refund.
