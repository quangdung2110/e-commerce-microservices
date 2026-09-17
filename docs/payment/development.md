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
(Maven), start PostgreSQL/Redis/Kafka/Connect, chạy migration (tạo partition
tháng hiện tại), start API server + Webhook Processor + scheduler, kiểm tra
health, rồi cleanup.

Local stack cần topic `t.payment.events.v2`, `t.payment.webhooks.raw`; connector
Debezium route `aggregate_type=Payment`. Stripe dùng
test mode (`sk_test_...`, `pk_test_...`) và Stripe CLI `listen --forward-to` tới
`POST /v1/payments/webhook/stripe` để lấy `whsec_...` local. Order có thể mock
bằng fixture gọi initiate/refund với `Idempotency-Key` ổn định.

## 3. Cấu hình local

Cần `.env.example`/profile local không chứa secret thật: DSN/pool PostgreSQL,
Redis, Kafka bootstrap/group, Stripe keys (test mode), `payment_session_ttl`,
webhook window 300s, lock TTL, interval các scheduler, S3 bucket (có thể mock/
tắt archival local). Tuyệt đối không commit `sk_*`, `whsec_*`, S3 credential.

## 4. Build, lint và format

Command chuẩn phải trùng CI và có tiêu chí pass rõ ràng. Ngoài compile/format/
lint, CI cần validate migration + partition, Spring State Machine config (mọi
transition trong bảng FSM có test), event serialization theo envelope, và
Markdown links/frontmatter. Chưa có command cụ thể để ghi tại đây.

## 5. Kiểm thử

### Unit test

- Payment FSM `T0`–`T9`, Refund FSM `R1`–`R4`, invalid transition, state
  precedence với webhook out-of-order.
- Guard: signature (đúng/sai/ngoài 300s), amount/reference mismatch, idempotency.
- Refund one-lifetime uniqueness per `order_id`, stable replay key, and amount
  `<= paid_amount`; selected-scope mixed PASS/FAIL must not block a valid Order command.
- Mapping Stripe event → transition; `charge.refunded` `status=failed` → reversal.
- Initiate/create and pre-capture cancel operation: stable local/provider key,
  timeout replay, provider reconciliation, and cancel-versus-capture race.

### Integration test

- PostgreSQL transaction: transition + outbox + status history atomic; optimistic
  lock conflict giữa hai webhook; `ON CONFLICT DO NOTHING` cho webhook duplicate.
- Redis: SETNX/lock hit/miss/outage; fallback `payment_idempotency` và
  `refund_transactions.idempotency_key`; worker reject khi không lấy được lock.
- Debezium outbox at-least-once, partition key `order_id`, connector restart.
- Stripe test mode/mock: `PaymentIntent.create` and `PaymentIntent.cancel` with
  repeated idempotency key and ambiguous transport result, `Refund.create` with
  repeated key, `Refund.retrieve` các trạng thái, `Webhook.constructEvent`.
- Scheduler: scanner expire đúng hạn và không expire khi Stripe đã `succeeded`;
  poller chốt khi webhook miss; reconciliation correction idempotent.
- Partition: insert vào đúng partition; archival job verify checksum trước drop.

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

Migration đầu tiên phải tạo the documented Payment tables, including
`provider_operations`, with constraint (`CHECK
provider`, unique `idempotency_key`, unique webhook dedup, PK `(key, action)`),
bổ sung `version` và `requires_manual_review` còn thiếu trong DB design cũ, tạo
`payment_transactions` `PARTITION BY RANGE (created_at)` và partition tháng hiện
tại (khuyến nghị `pg_partman`). Cần command tạo/up/down/verify migration, seed và
rollback policy; destructive down migration không xóa transaction production.

## 8. Test data và fixture

Fixture tối thiểu: payment `PENDING`/`SUCCESS`/`FAILED`/`EXPIRED`/`REFUNDING`/
`REFUNDED`; refund `PENDING`/`PROCESSING`/`SUCCESS`/`FAILED`; webhook payload
Stripe test cho `payment_intent.succeeded`, `payment_intent.payment_failed`,
`payment_intent.canceled`, `charge.refunded` (success và failed). Dùng Stripe
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
- Partition tháng hiện tại chưa tạo → insert lỗi.
- Redis lock TTL quá ngắn so với Stripe latency → gọi trùng.
- Amount VND subunit sai quy ước (`× 100`) so với Stripe.

## 11. Definition of Done

Một thay đổi chỉ hoàn tất khi build/test pass; migration và rollback được kiểm
tra; transition + outbox + audit atomic không bị phá; guard signature/amount/
idempotency có test; interface/data/workflow/operations docs được cập nhật;
breaking decision có ADR mới; secret/PII không log; evidence/frontmatter được cập
nhật trước khi nâng trạng thái khỏi `proposed`.
