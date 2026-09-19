---
doc_id: payment-architecture
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Kiến trúc

> File này mô tả kiến trúc mục tiêu được chuyển từ thiết kế cũ (PostgreSQL-first,
> Stripe SDK direct, webhook async qua Kafka, Outbox CDC). Chưa có source path hoặc
> deployment config để đánh dấu `implemented`/`verified`.

## 1. Bối cảnh kiến trúc

Payment nằm giữa Order Service và Stripe. Order (Temporal Activity) gọi Payment để
tạo PaymentIntent và để hoàn tiền; Stripe gọi ngược Payment qua webhook; Payment
phát kết quả lên Kafka để Order signal workflow. Payment không nhận request trực
tiếp từ Buyer cho command tài chính — frontend chỉ dùng `client_secret` với Stripe
Elements/Checkout SDK và Stripe là bên thông báo kết quả.

Từ Phase 18 hệ thống chỉ dùng Stripe: bỏ VNPay/MoMo/ZaloPay, bỏ COD, bỏ Provider
Adapter/Factory Pattern. Toàn bộ Stripe-specific code nằm trong package
`com.payment.stripe`, application layer gọi qua interface `PaymentProviderPort`
với đúng một implementation `StripeAdapter`.

```mermaid
flowchart LR
  O[Order Service<br/>Temporal Activities] -->|initiate / cancel / refund| API[Payment HTTP v1 adapter]
  FE[Frontend<br/>Stripe Elements] -->|client_secret| ST[Stripe]
  ST -->|webhook| WH[Webhook endpoint<br/>POST /v1/payments/webhook/stripe]
  WH -->|raw dump| KR[(Kafka t.payment.webhooks.raw)]
  KR --> WP[Webhook Processor Worker]
  API --> APP[Application / FSM layer]
  WP --> APP
  SCH[Schedulers:<br/>Expiration, Reconciliation,<br/>Refund Poller, Webhook Retry,<br/>Provider-op Recovery] --> APP
  APP --> PG[(PostgreSQL)]
  APP --> RD[(Redis: idempotency + locks)]
  APP -->|PaymentProviderPort| SA[com.payment.stripe<br/>StripeAdapter]
  SA --> ST
  PG --> DBZ[Debezium / Kafka Connect]
  DBZ --> KP[(Kafka t.payment.events.v2)]
  KP --> O
  KP --> AN[Analytics]
```

Trust boundary chính: webhook từ Internet phải được xác thực `Stripe-Signature`
trước khi bất kỳ business payload nào được parse; API command chỉ chấp nhận từ
Order Service (service credential); Kafka message từ Order được coi là dữ liệu
không tin cậy cho đến khi validate lane và trạng thái transaction.

## 2. Thành phần và trách nhiệm

| Thành phần | Trách nhiệm | Không được làm |
|---|---|---|
| HTTP v1 adapter | Nhận `initiate`, `cancel`, `refund`, `query`, `client-data`; validate input; kiểm tra idempotency (`payment_idempotency` fingerprint + in-progress); gọi application service | Chứa business FSM; gọi Stripe trực tiếp |
| Webhook endpoint | Nhận raw body + header Stripe, trả 200 trong < 500ms, produce lên `t.payment.webhooks.raw` | Verify signature, parse payload hoặc ghi DB đồng bộ |
| Application/FSM layer | `@Transactional` gom transaction + outbox + status history; Spring State Machine cho Payment FSM và Refund FSM; guard signature/amount/reference/idempotency | Publish Kafka trực tiếp; bỏ qua guard |
| `com.payment.stripe` (`PaymentIntentClient`, `RefundClient`, `WebhookVerifier`) | Wrap `PaymentIntent.create/retrieve`, `Refund.create/retrieve`, `Webhook.constructEvent`; dùng một `StripeClient` singleton với API key từ Vault/Secret Manager | Chứa logic nghiệp vụ Payment; lộ Stripe type ra ngoài package |
| Webhook Processor Worker | Consume raw webhook, verify signature, map event → transition, dedup, update DB + outbox trong một ACID transaction | Bỏ qua verify khi retry; ack trước khi commit |
| Payment Expiration Scanner | Với `PENDING` quá `expires_at`: persist `ProviderOperation CANCEL(SESSION_EXPIRED)` → `PaymentIntent.cancel` → `EXPIRED` + `PAYMENT_EXPIRED`; cancel trả `succeeded` → `SUCCESS` | Expire bằng suy luận khi intent chưa canceled; expire `INITIATING`/`PROCESSING` |
| Provider-operation Recovery Worker | Replay `provider_operations` `REQUESTED`/`UNKNOWN` quá `next_retry_at` với cùng key; bind identity hoặc mở review case `PROVIDER_IDENTITY_UNBOUND` | Sinh key mới; suy luận non-capture |
| Reconciliation Worker | Đối soát hourly với Stripe, ghi `reconciliation_runs`, phát correction transition `CORR` khi không freeze, mở review case `RECONCILIATION_DRIFT` | Sửa dữ liệu ngoài transition hợp lệ; correction khi `auto_correction_frozen` |
| Refund Status Poller | Poll `Refund.retrieve` cho refund `PROCESSING`, chốt trạng thái cuối khi webhook miss | Gọi Stripe khi không lấy được lock |
| Webhook Auto-Retry Worker | Đẩy lại webhook `FAILED_SIGNATURE`/`UNPROCESSED`/`FAILED_PROCESSING` vào Kafka theo `next_retry_at`; hết retry với event tài chính → review case `WEBHOOK_RETRY_EXHAUSTED` | Bỏ qua `max_retries` |
| Dead-letter Replay Worker | Replay thủ công theo filter, re-validate idempotency, batch nhỏ có rate limit | Replay không audit |
| PostgreSQL | Source of truth cho transaction, provider operation, refund, webhook log, audit, outbox, idempotency, review case; một OLTP schema, không partition/archive ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)) | Shared database cho service khác |
| Redis (Lettuce/Redisson) | Idempotency pointer `payment:idem:{action}:{key}`, distributed lock `payment:lock:{payment_transaction_id}` | Source of truth cho trạng thái; cấp quyền cho side effect thứ hai |
| Debezium/Kafka Connect | Đọc WAL outbox, route `aggregate_type=Payment` → `t.payment.events.v2` với partition key `order_id` | Cần cột polling-style trong outbox |

## 3. Mô hình xử lý

### 3.1. Đồng bộ

- `POST /v1/payments/initiate`: local transaction persists `INITIATING` plus stable
  provider operation → Stripe `PaymentIntent.create` with that same key → only a
  proven provider result becomes `PENDING` + client secret/outbox. An uncertain
  create is replayed/reconciled, never represented as a blank PENDING transaction.
- `POST /v1/payments/{transactionId}/cancel`: local transaction persists/replays a
  stable cancel operation → `PaymentIntent.cancel` with the same provider key →
  returns non-capture or `ALREADY_SUCCEEDED`. Order must wait for this fact before it
  finalizes cancellation.
- `POST /v1/payments/{transactionId}/refund`: validate + local transaction →
  distributed lock → Stripe `Refund.create` với `Idempotency-Key` → trả
  `PROCESSING`. Response "accepted" không phải "final".
- `GET /v1/payments/{transactionId}`: Order workload đọc PostgreSQL, join refunds.
- `GET /v1/payments/{transactionId}/client-data`: giải mã `client_secret` chỉ khi
  `PENDING` và chưa quá `client_expires_at`; Payment là nơi lưu duy nhất, Order relay
  không lưu.
- Webhook endpoint: đồng bộ chỉ ở mức nhận và produce Kafka; trả 200 ngay.

### 3.2. Bất đồng bộ và transaction boundary

- Webhook đi đường Raw → Kafka `t.payment.webhooks.raw` → Webhook Processor
  Worker. Lý do: Stripe retry nếu response > 5s (3–5 lần, exponential backoff) gây
  duplicate; DB down lúc webhook đến sẽ mất event vĩnh viễn; Kafka at-least-once +
  worker idempotent cho "không mất, không trùng"
  ([ADR-004](./decisions/ADR-004-async-webhook-processing-via-kafka.md)).
- Mỗi state transition được commit trong một transaction PostgreSQL gồm: update
  transaction (optimistic lock `version`) + insert `payment_status_history` +
  insert `outbox_events`. Debezium relay sau commit
  ([ADR-005](./decisions/ADR-005-transactional-outbox-cdc.md)).
- Side effect ngoài (gọi Stripe) không nằm trong DB transaction. Every create,
  cancel and refund first persists a durable provider-operation key/fingerprint;
  timeout recovery replays or reconciles that operation before changing FSM. Với
  refund, refund row `PENDING` + payment
  `REFUNDING` được commit trước khi gọi `Refund.create`; kết quả cuối đến qua
  webhook `charge.refunded` (success) / `charge.refund.updated` `status=failed`
  (failed hoặc reversed), poller hoặc reconciliation.
- Decline (`payment_intent.payment_failed`) là sự kiện phi terminal; Payment chỉ
  đưa attempt vào `FAILED`/`EXPIRED`/`CANCELED` sau khi PaymentIntent đã canceled qua
  một `ProviderOperation CANCEL`
  ([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)).
- Scheduled jobs dùng Spring `@Scheduled` (+ Spring Retry khi gọi Stripe).

### 3.3. Mapping Stripe event → FSM

| Stripe event | Transition | Kết quả |
|---|---|---|
| `payment_intent.processing` | `T1` | `PROCESSING`, không phát event |
| `payment_intent.succeeded` | `T2` `PAYMENT_SUCCESS_CONFIRMED` | `SUCCESS`, phát `PAYMENT_SUCCESS` |
| `payment_intent.payment_failed` | `T3a` `PAYMENT_DECLINED` (retryable) hoặc `CANCEL` operation → `T3` (non-retryable/quá ngưỡng) | Giữ `PENDING`, không event; hoặc `FAILED`, phát `PAYMENT_FAILED` |
| `payment_intent.canceled` | `T3`/`T4`/`T5` theo `CANCEL` operation đã ghi | `FAILED`/`EXPIRED`/`CANCELED`; thường no-op vì response cancel đồng bộ đã áp |
| `charge.refunded` | `R2` + `T7` | Refund `SUCCESS`, payment `REFUNDED`, phát `REFUND_SUCCESS` |
| `charge.refund.updated` / `refund.failed` (`status=failed`) | `R3` + `T8` (đang `PROCESSING`) hoặc `R5` + `T9` (đã `SUCCESS`) | `REFUND_FAILED` hoặc `REFUND_REVERSED`; mở review case |
| `charge.dispute.created` | — | Review case `DISPUTE_OPENED` (chưa có FSM) |

## 4. Mô hình nhất quán

| Phạm vi | Mô hình | Cơ chế |
|---|---|---|
| Trạng thái transaction/refund | Strong/local ACID | PostgreSQL transaction + optimistic lock `version` |
| State và event Payment phát ra | Atomic local, eventual publish | Transactional outbox + Debezium CDC |
| Trạng thái thật trên Stripe ↔ DB nội bộ | Eventual, có reconciliation | Webhook + poller + reconciliation hourly có quyền phát transition bù |
| Order nhìn thấy kết quả thanh toán | Eventual | `t.payment.events.v2` at-least-once, partition theo `order_id` |
| Redis idempotency pointer/lock | Tạm thời, có thể mất | TTL; fallback DB unique constraint |
| Lịch sử giao dịch mọi tuổi | Một OLTP schema, một đường đọc | Không partition/archive; partial index giữ tập hoạt động nhỏ ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)) |

Source of truth là PostgreSQL transaction. Khi provider và nội bộ lệch trạng thái,
Reconciliation job có quyền phát transition bù để đưa hệ thống về trạng thái đúng.
Chưa có SLO chính thức cho độ trễ publish; SLO gợi ý trong `operations.md`.

## 5. Mô hình đồng thời và ordering

Chiến lược kết hợp ([ADR-006](./decisions/ADR-006-optimistic-lock-and-redis-lock-strategy.md)):

- **Optimistic Locking (cột `version`)** là default guard cho **tất cả** state
  transition (ví dụ hai webhook Stripe ghi đè nhau). Không tốn overhead Redis.
- **Distributed Lock (Redis Redisson)** chỉ để giảm đồng thời trên cùng payment khi
  gọi Stripe hoặc khi scheduler chạy nhiều instance: `payment:lock:{payment_transaction_id}`
  (create/cancel/refund/poll trên cùng payment), `payment:lock:reconciliation`
  (singleton). TTL 15–30s. Lock **không** cấp quyền cho side effect: quyền đó thuộc
  `provider_operations` đã commit với key bất biến ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).
- **Redis degradation:** API read/reconciliation không ảnh hưởng; command idempotency
  luôn có authority ở DB (`payment_idempotency` PK + fingerprint,
  `refund_transactions.command_idempotency_key`/`order_id`,
  `provider_operations.provider_idempotency_key`); worker không lấy được lock thì
  reject và đẩy vào retry cycle thay vì ignore lock.
- **Webhook dedup:** `INSERT INTO webhook_logs ... ON CONFLICT (stripe_event_id) DO
  NOTHING`; 0 rows → đã xử lý → ack. Không dedup theo `(pi, event_type)` vì Stripe hợp
  lệ bắn nhiều `payment_failed` trên cùng intent.
- **Out-of-order:** áp rule state precedence theo bảng FSM và `event_created_at`;
  không override ngược trạng thái terminal hợp lệ; `succeeded` sau terminal
  non-capture → review case `CAPTURE_AFTER_CANCEL`.
- **Ordering downstream:** partition key `order_id` cho mọi event của cùng đơn
  (payment + refund) để Order consumer nhận strict ordering
  ([ADR-007](./decisions/ADR-007-order-id-partition-key.md)).
- **Refund double spend:** unique `refund_transactions.order_id` plus
  `new_amount <= paid_amount` are checked atomically; `Idempotency-Key` do Order
  sinh và ổn định qua retry được truyền sang Stripe.

## 6. Mô hình triển khai

Thiết kế yêu cầu các process/runtime logic sau, chưa chốt chạy chung hay tách pod:

- Payment HTTP v1 API server (initiate, refund, query, webhook endpoint).
- Webhook Processor consumer trên `t.payment.webhooks.raw`.
- Scheduled jobs: Expiration Scanner (1–2 phút), Provider-operation Recovery,
  Reconciliation (hourly), Refund Status Poller (5–15 phút), Webhook Auto-Retry,
  retention trimmer hàng ngày.
- PostgreSQL (một schema, không partition), Redis, Kafka.
- Debezium/Kafka Connect outbox connector.

API server scale ngang. Webhook Processor scale theo partition của
`t.payment.webhooks.raw`. Các cronjob quét transaction phải dùng distributed lock
để nhiều instance không xử lý trùng; poller lock theo từng refund. Reconciliation và
trimmer chạy singleton.

## 7. Ràng buộc kiến trúc

- Không service nào ghi trực tiếp vào database Payment; Payment không query
  database service khác.
- Không phát event trước khi transaction commit; `outbox_events` append-only,
  cấm cột polling-style (retry/DLQ/backoff thuộc Kafka Connect runtime).
- Không xử lý webhook đồng bộ tại endpoint; verify signature luôn xảy ra trước
  state transition.
- Không đặt business logic trong controller, Kafka adapter hay package
  `com.payment.stripe`.
- Không suy luận refund từ Order/shipping event; refund chỉ từ explicit command
  của Order. Payment không đăng ký consumer trên `t.order.events`.
- Payment is authoritative for the pre-capture cancel/capture race. Chỉ cancel
  command đồng bộ và provider fact có thể thay đổi financial state; một
  `SUCCESS` fact không thể bị biến thành `CANCELED` bằng suy luận.
- Không hỗ trợ COD hoặc provider ngoài Stripe nếu chưa có ADR thay thế.
- Giữ code boundary Refund rõ (`src/refund/`, interface riêng, contract độc lập)
  để tách service khi đạt ngưỡng Phase 2.

## 8. Đánh đổi và ADR liên quan

- [ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md): Java/Spring Boot cho
  FSM framework, `@Transactional` và Stripe SDK mature; đổi lại nặng hơn Go.
- [ADR-002](./decisions/ADR-002-refund-inside-payment-service.md): Refund trong
  Payment giữ transaction boundary cho one-refund-per-order và amount guard; đổi
  lại phải tách sau khi refund ratio > 5%.
- [ADR-011](./decisions/ADR-011-one-lifetime-order-refund.md): Order supplies one
  stable case and Inventory evidence; provider fail/reverse remains manual review,
  not a second refund.
- [ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md): provider
  create/cancel side effects have durable identities and Order waits for the
  cancel/capture fact; đổi lại adds recovery state and reconciliation work.
- [ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md): bỏ adapter
  pattern giảm phức tạp; đổi lại vendor lock-in với Stripe.
- [ADR-004](./decisions/ADR-004-async-webhook-processing-via-kafka.md): webhook
  async không mất event; đổi lại thêm topic nội bộ và độ trễ xử lý.
- [ADR-005](./decisions/ADR-005-transactional-outbox-cdc.md): CDC giữ atomicity;
  đổi lại phụ thuộc WAL/Kafka Connect và migration từ polling.
- [ADR-006](./decisions/ADR-006-optimistic-lock-and-redis-lock-strategy.md):
  optimistic lock rẻ, Redis lock chỉ cho side effect; đổi lại cần fallback khi
  Redis sập.
- [ADR-007](./decisions/ADR-007-order-id-partition-key.md): partition theo
  `order_id` cho strict ordering; đổi lại `aggregate_id` và routing key khác nhau.
- [ADR-008](./decisions/ADR-008-minimal-event-payload-phase-20.md): payload tối
  thiểu cho Order; đổi lại Analytics phải join thêm.
- [ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md): một OLTP
  schema, không partition/S3/tiered read (thay ADR-009); uniqueness và FK enforce
  đầy đủ; đổi lại bảng tăng đơn điệu, cần theo dõi bloat.
- [ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md):
  decline không terminal, mọi terminal non-capture đòi hỏi intent đã canceled; đổi
  lại thêm round-trip cancel trước `FAILED`/`EXPIRED` và policy decline code.
- [ADR-010](./decisions/ADR-010-refund-only-by-explicit-order-command.md): refund
  chỉ từ Order command giữ boundary rõ và loại bỏ dependency vào Order event.
