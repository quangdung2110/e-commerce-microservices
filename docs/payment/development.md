---
doc_id: payment-development
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Hướng dẫn phát triển

> Repository chưa có implementation Payment Service để suy ra command, port,
> package hay migration. File này là checklist triển khai/kiểm thử trung thực với
> thiết kế (tech stack đã chốt ở [ADR-001](./decisions/ADR-001-java-spring-boot-runtime.md)),
> không bịa lệnh copy-paste chưa tồn tại.

## 1. Điều kiện tiên quyết

Tech stack đã chốt: JDK 21, Maven, Spring Boot 3.x + Spring Web, Spring State
Machine, Spring Data JPA (PostgreSQL), Spring Kafka, Redis client Lettuce
(idempotency) và Redisson (distributed lock), `javax.crypto` cho HMAC, Spring
WebClient/OkHttp, Spring `@Scheduled` + Spring Retry, Stripe Java SDK
(`com.stripe:stripe-java`). Cần thêm Docker/Compose cho PostgreSQL, Redis, Kafka,
Kafka Connect/Debezium và Stripe CLI để forward webhook local.

## 2. Chạy local và dependency

Khi implementation được thêm, tài liệu phải cung cấp command chính thức để build
(Maven), start PostgreSQL/Redis/Kafka/Connect, chạy migration, start API server +
Webhook Processor + scheduler, kiểm tra health, rồi cleanup.

Local stack cần topic `t.payment.events.v2`, `t.payment.webhooks.raw`; connector
Debezium route `aggregate_type=Payment`. Stripe dùng
test mode (`sk_test_...`, `pk_test_...`) và Stripe CLI `listen --forward-to` tới
`POST /v1/payments/webhook/stripe` để lấy `whsec_...` local. Order có thể mock
bằng fixture gọi initiate/refund với `Idempotency-Key` ổn định.

## 3. Cấu hình local

Cần `.env.example`/profile local không chứa secret thật: DSN/pool PostgreSQL,
Redis, Kafka bootstrap/group, Stripe keys (test mode), `payment_session_ttl`,
webhook window 300s, lock TTL, interval các scheduler, `max_declines_per_attempt`,
danh sách decline code non-retryable, key KMS local (dev key cố định, không dùng ở
staging trở lên) cho `client_secret_ciphertext`. Tuyệt đối không commit `sk_*`,
`whsec_*`, KMS key thật.

## 4. Build, lint và format

Command chuẩn phải trùng CI và có tiêu chí pass rõ ràng. Ngoài compile/format/
lint, CI cần validate migration (không `PARTITION BY`, đủ unique/partial index theo
`data.md §4`), Spring State Machine config (mọi transition trong bảng FSM có test), event serialization theo envelope, và
Markdown links/frontmatter. Chưa có command cụ thể để ghi tại đây.

## 5. Kiểm thử

### Chiến lược kiểm thử (Testing strategy)

Stripe là dependency ngoài duy nhất của Payment ([ADR-003](./decisions/ADR-003-stripe-single-provider-direct-sdk.md)),
nên chiến lược test xoay quanh câu hỏi: **mỗi tầng test "nói chuyện" với Stripe
bằng hình thái nào?** Càng lên tầng cao, độ trung thực càng tăng nhưng tốc độ,
tính xác định và khả năng chạy offline càng giảm.

| Tầng | Hình thái Stripe | Hạ tầng | Khi nào chạy |
|---|---|---|---|
| Unit | Không gọi Stripe. `PaymentProviderPort` được fake/mock; webhook payload là fixture tự ký bằng `whsec_` giả | Không | Mọi commit, offline |
| Integration | `stripe-mock` (container) cho contract SDK ↔ API shape; WireMock cho kịch bản `stripe-mock` không mô phỏng được | Testcontainers: PostgreSQL, Redis, Kafka + Connect | Mọi PR, offline |
| E2E | Stripe **test mode** (sandbox riêng) + Stripe CLI `listen`/`trigger` + test card | Docker Compose local hoặc staging | Thủ công khi dev; nightly trên `main` |
| Failure injection | WireMock fault (timeout, 5xx, ambiguous response) cho Stripe; Toxiproxy cho Redis/Kafka/DB | Testcontainers | Nightly / trước release |

**Ranh giới giữa các hình thái Stripe.**

- `stripe-mock` **stateless**: không lưu object giữa các call, không bắn
  webhook, không kiểm tra `Idempotency-Key`, trả dữ liệu sinh từ OpenAPI spec.
  Chỉ dùng để khẳng định code gọi SDK đúng shape (request/response deserialize
  được). Mọi assertion về *hành vi* (replay cùng key trả cùng `pi_xxx`,
  cancel-versus-capture, `Refund.retrieve` đổi trạng thái) phải dùng WireMock
  với stub có state, hoặc test mode thật.
- Ambiguous transport result (timeout sau khi Stripe đã tạo PaymentIntent) chỉ
  mô phỏng được bằng WireMock fault; test mode thật không tái hiện được có chủ
  đích. Đây là kịch bản bắt buộc cho recover flow
  ([ADR-012](./decisions/ADR-012-recoverable-initiation-and-cancel.md)).
- `Webhook.constructEvent` là hàm thuần (HMAC-SHA256 trên `t.payload`), test
  offline hoàn toàn: ký fixture bằng `whsec_` giả, biến thể `t` ngoài cửa sổ
  300s, `v1` sai, payload bị sửa sau khi ký.

**Môi trường Stripe.**

- Mỗi môi trường một Stripe Sandbox riêng (dev-local mỗi người một sandbox,
  `staging`, `ci` nếu CI gọi test mode). Không dùng chung `sk_test_`/`whsec_`
  giữa môi trường để tránh webhook của người này bắn vào máy người kia.
- Không tầng test nào được dùng live key; CI phải fail nếu phát hiện chuỗi
  `sk_live_` / `pk_live_` trong config hoặc log.
- Ràng buộc đã biết: Stripe **live** chưa hỗ trợ merchant đăng ký tại Việt Nam;
  test mode không bị giới hạn. Toàn bộ vòng đời dev/demo chạy trên test mode;
  go-live cần pháp nhân ở quốc gia Stripe hỗ trợ (ghi nhận ở
  [operations.md](./operations.md)).

**Test card ↔ trạng thái đích** (dùng ở tầng E2E; số card theo tài liệu Stripe,
cần kiểm tra lại khi Stripe đổi).

| Test card | Kết quả Stripe | Event webhook | Trạng thái Payment đích |
|---|---|---|---|
| `4242 4242 4242 4242` | Thành công ngay | `payment_intent.succeeded` | `SUCCESS` |
| `4000 0025 0000 3155` | Yêu cầu 3D Secure | `requires_action` → sau xác thực `payment_intent.succeeded` | `PENDING` → `SUCCESS` |
| `4000 0000 0000 0002` | `card_declined` (`generic_decline`, retryable) | `payment_intent.payment_failed` | `T3a`: vẫn `PENDING`, `decline_count=1`; thanh toán lại bằng `4242…` → `SUCCESS` |
| `4000 0000 0000 9995` | `insufficient_funds` (retryable) | `payment_intent.payment_failed` | `T3a`: vẫn `PENDING` |
| `4000 0000 0000 9979` | `stolen_card` (non-retryable) | `payment_failed` → Payment `PaymentIntent.cancel` → `payment_intent.canceled` | `T3` `FAILED`, `PAYMENT_FAILED error_code=STOLEN_CARD` |
| `4000 0000 0000 0002` × 6 lần | Quá `max_declines_per_attempt` | như trên | `T3` `FAILED`, `error_code=DECLINE_LIMIT_EXCEEDED` |
| `4000 0000 0000 0259` | Thành công rồi bị dispute | `charge.dispute.created` | `SUCCESS` + review case `DISPUTE_OPENED` |
| `4000 0000 0000 5126` | Thanh toán thành công, refund thất bại async | `charge.refund.updated` `status=failed` | `R3`/`T8` (nếu còn `PROCESSING`) hoặc `R5`/`T9` `REVERSED` (nếu đã `SUCCESS`) + review case |
| Không thanh toán trong `payment_session_ttl` | — | Scanner `PaymentIntent.cancel` → `payment_intent.canceled` | `T4` `EXPIRED` (chỉ sau khi intent canceled) |

**Kịch bản webhook không tạo được bằng thanh toán thường.**

- Duplicate: `stripe trigger <event>` hai lần, hoặc "Resend" từ Dashboard →
  dedup theo `stripe_event_id`, `ON CONFLICT DO NOTHING`.
- Out-of-order (`payment_failed` đến sau `succeeded`): Stripe CLI không kiểm soát
  thứ tự; replay fixture đã ký thẳng vào `POST /v1/payments/webhook/stripe`
  theo thứ tự chủ đích (tầng integration).
- Webhook miss: tắt `stripe listen`, thanh toán bằng test card, kiểm tra
  Reconciliation (payment) hoặc Refund Poller (refund) chốt trạng thái từ
  `PaymentIntent.retrieve`/`Refund.retrieve`; bật lại `listen` để xác nhận webhook
  đến muộn bị bỏ qua idempotent.
- Đổi `whsec_`: restart `stripe listen` sinh secret mới → xác nhận
  `FAILED_SIGNATURE` rồi auto-retry sau khi cập nhật config.
- Retry 3–5 lần của Stripe: endpoint trả 5xx có chủ đích (feature flag test) →
  quan sát Stripe retry và xác nhận không xử lý trùng.

**Thời gian.** Stripe Test Clocks chỉ áp dụng cho Billing/subscription, không
đẩy nhanh timeout của PaymentIntent. Scheduler 15 phút và poller 5–15 phút phải
nhận `java.time.Clock` inject để unit/integration test tua thời gian; tầng E2E
dùng profile test với interval rút ngắn thay vì chờ thật.

**Cổng CI.**

- Mỗi PR: unit + integration (offline, `stripe-mock`/WireMock/Testcontainers).
  Không cần secret Stripe.
- Nightly trên `main` / trước release: E2E test mode với key sandbox `ci` lấy
  từ CI secret store; failure injection đầy đủ.
- Bất kỳ transition nào trong bảng FSM (`domain.md §6`) chưa có test ở tầng unit
  → CI fail.

Các mục dưới đây liệt kê case cụ thể cho từng tầng.

### Unit test

- Payment FSM `T0`–`T9` (kể cả `T3a` audit-only), Refund FSM `R1`–`R5`, `CORR`,
  invalid transition, state precedence theo `event_created_at`.
- Policy decline (`RULE-PAY-014`): retryable → `PENDING`; non-retryable/quá ngưỡng →
  `CANCEL` operation → `FAILED`; cancel trả `succeeded` → `T2`.
- `financial_state_version` +1 đúng một lần cho mỗi outbox event, không tăng ở `T3a`.
- `client-data`: `usable` đúng theo `status`/`client_expires_at`; ciphertext bị xóa
  khi rời `PENDING`; key rotation giải mã được key cũ.
- Guard: signature (đúng/sai/ngoài 300s), amount/reference mismatch, idempotency.
- Refund one-lifetime uniqueness per `order_id`, stable replay key, and amount
  `<= paid_amount`; selected-scope mixed PASS/FAIL must not block a valid Order command.
- Mapping Stripe event → transition theo `interfaces.md §5.1`; `charge.refund.updated`
  `status=failed` → `R3`/`T8` hay `R5`/`T9` tùy trạng thái refund hiện tại;
  `payment_intent.canceled` → terminal theo `CANCEL` operation đã ghi.
- Initiate/create and pre-capture cancel operation: stable local/provider key,
  timeout replay, provider reconciliation, and cancel-versus-capture race.

### Integration test

- PostgreSQL transaction: transition + outbox + status history atomic; optimistic
  lock conflict giữa hai webhook; `ON CONFLICT (stripe_event_id) DO NOTHING` cho
  webhook duplicate; hai `payment_failed` khác `evt_` trên cùng intent đều được ghi;
  partial unique một `CANCEL`/payment khi scanner và Order cancel đồng thời; unique
  `refund_transactions.order_id` khi hai refund khác key.
- Redis: SETNX/lock hit/miss/outage; fallback `payment_idempotency` (fingerprint +
  `IN_PROGRESS`) và `refund_transactions.command_idempotency_key`; worker reject khi
  không lấy được lock.
- Debezium outbox at-least-once, partition key `order_id`, connector restart.
- Stripe test mode/mock: `PaymentIntent.create` and `PaymentIntent.cancel` with
  repeated idempotency key and ambiguous transport result, `Refund.create` with
  repeated key, `Refund.retrieve` các trạng thái, `Webhook.constructEvent`.
- Scheduler: scanner cancel intent rồi expire; không expire `INITIATING`/`PROCESSING`;
  cancel trả `succeeded` → `SUCCESS`; poller chốt khi webhook miss; recovery worker
  replay cùng key và bind `pi_`/`re_`; reconciliation `CORR` idempotent và chỉ mở
  review case khi freeze; retention trimmer không xóa outbox trên CDC watermark.
- Review case: partial unique một case mở/payment; `requires_manual_review` đồng bộ
  với case; đóng thiếu `resolution_type` bị reject.

### Contract/consumer/provider test

Implementation must contract-test the documented Payment event version 2 fields:
`payment_transaction_id`, `currency` and monotonic `financial_state_version`.
Tests for the synchronous cancel command must cover and resolve the cancel/capture
race without relying on an Order event consumer.

### End-to-end/failure injection

Initiate with lost provider response → recover → Stripe test payment → webhook →
`PAYMENT_SUCCESS`; cancel-versus-capture race; payment failed/canceled/expired;
one Order selected-line or admin-pre-dispatch refund, `succeeded` ngay vs `pending`, webhook
miss, reversal; Redis/Kafka/DB/Stripe outage; Stripe retry webhook 3–5 lần; đổi
webhook secret + auto-retry. Timer dài (15 phút, poll 5–15 phút) cần test clock.

## 6. Contract code generation

Payment HTTP v1 và events v2 are the source contracts at
`contracts/http/payment/v1/openapi.yaml` and
`contracts/events/payment/v2/asyncapi.yaml`. CI must lint both, pin generator/tool
versions, generate into a declared output path, and fail on drift. Markdown examples are
not generated models. Provider and Order consumer fixtures must assert schema v2,
attempt/transaction mapping, VND money and absence of `client_secret` from events.

## 7. Database migration

Migration đầu tiên tạo đủ 9 bảng theo DDL `data.md §3` (`payment_transactions`,
`provider_operations`, `refund_transactions`, `webhook_logs`, `payment_status_history`,
`outbox_events`, `payment_idempotency`, `payment_review_cases`, `reconciliation_runs`)
với mọi `CHECK`, unique và partial index ở `data.md §4`; **không** `PARTITION BY`
([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)). Thứ tự tạo:
`payment_transactions` → `refund_transactions` → `provider_operations` (FK cả hai) →
còn lại. Cần command tạo/up/down/verify migration, seed và rollback policy;
destructive down migration không xóa transaction production.

## 8. Test data và fixture

Fixture tối thiểu: payment `INITIATING`/`PENDING`/`PROCESSING`/`SUCCESS`/`FAILED`/
`EXPIRED`/`CANCELED`/`REFUNDING`/`REFUNDED`; refund `PENDING`/`PROCESSING`/`SUCCESS`/
`FAILED`/`REVERSED`; `provider_operations` ở `REQUESTED`/`UNKNOWN`/`PROVIDER_CONFIRMED`;
review case mở/đóng; webhook payload Stripe test cho `payment_intent.processing`,
`payment_intent.succeeded`, `payment_intent.payment_failed` (retryable và
non-retryable), `payment_intent.canceled` (có/không `CANCEL` operation),
`charge.refunded`, `charge.refund.updated` `status=failed`, `charge.dispute.created`. Dùng Stripe
test keys/objects, UUID giả; không
dùng dữ liệu production.

## 9. Gỡ lỗi

Trace bằng `correlation_id`, `order_id`, `payment_transaction_id`,
`stripe_event_id`. Inspect `webhook_logs.payload` và `payment_status_history`
trước khi replay; kiểm tra Stripe Dashboard/`Refund.retrieve` khi nghi lệch;
kiểm tra Kafka offset/lag và Redis lock TTL. Không update FSM trực tiếp để "gỡ
kẹt" vì bỏ audit/outbox; dùng correction transition hoặc manual review.

## 10. Lỗi local thường gặp cần ghi khi có code

- `FAILED_SIGNATURE` do `whsec_` local (Stripe CLI) khác Dashboard.
- Đồng hồ máy lệch > 300s làm reject webhook.
- Debezium không route `aggregate_type=Payment` hoặc partition key sai.
- Redis lock TTL quá ngắn so với Stripe latency → gọi trùng.
- Nhân `amount_minor` với 100 trước khi gửi Stripe: VND là zero-decimal currency,
  `amount_minor` truyền nguyên (100000 = 100.000 ₫); nhân 100 sẽ tính sai 100 lần.
- Dev key KMS local khác nhau giữa hai lần chạy → `client-data` trả `usable=false`
  dù attempt còn `PENDING`.
- Coi `payment_intent.payment_failed` là terminal trong test → sai với ADR-014.

## 11. Definition of Done

Một thay đổi chỉ hoàn tất khi build/test pass; migration và rollback được kiểm
tra; transition + outbox + audit atomic không bị phá; guard signature/amount/
idempotency có test; interface/data/workflow/operations docs được cập nhật;
breaking decision có ADR mới; secret/PII không log; evidence/frontmatter được cập
nhật trước khi nâng trạng thái khỏi `proposed`.
