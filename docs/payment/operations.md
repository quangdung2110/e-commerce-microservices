---
doc_id: payment-operations
service: payment
scope: target
status: proposed
owners:
  - Payment Service team
evidence: []
verified_against_commit: null
---

# Payment Service — Vận hành

> File này gom SLO gợi ý, worker, observability, failure mode và runbook rút gọn từ
> thiết kế cũ. Chưa có runtime config, dashboard hay alert rule thực tế để xác minh.

## 1. SLO, SLI và health

SLO gợi ý của thiết kế cũ (chưa phải cam kết đo được):

| SLI | Mục tiêu gợi ý |
|---|---|
| Outbox publish latency | 99.9% event publish trong < 60s sau commit |
| Webhook processing latency | 99% webhook hợp lệ xử lý trong < 2 phút |
| Refund finalization | 99% refund có trạng thái cuối trong SLA Stripe cam kết (vài giây cho `succeeded`, vài ngày cho issuing bank finalization) |
| Webhook endpoint response | Trả 2xx trong < 500ms (Stripe retry nếu > 5s) |

Health endpoint: chưa được thiết kế cũ mô tả. Khi có code, readiness phải phụ
thuộc PostgreSQL và Kafka producer (webhook endpoint không được nhận traffic nếu
không produce được); Redis không nên chặn readiness vì có fallback DB.

## 2. Cấu hình cần công bố

| Cấu hình | Mục đích | Secret |
|---|---|---|
| Stripe API key (`sk_...`) | `StripeClient` singleton | Có — Vault/Secret Manager |
| Stripe webhook secret (`whsec_...`) | Verify `Stripe-Signature` | Có — Vault/Secret Manager |
| Stripe publishable key (`pk_...`) | Trả cho frontend | Không |
| `payment_session_ttl` | 15 phút; `expires_at` và TTL Redis (+60s) | Không |
| Webhook timestamp window | 300s | Không |
| Redis lock TTL | 15–30s | Không |
| Expiration Scanner interval | 1–2 phút | Không |
| Reconciliation interval | hourly | Không |
| Refund Poller interval | 5–15 phút, jitter | Không |
| Poller `updated_at` threshold | 1 phút | Không |
| Webhook retry `max_retries`/backoff | Auto-Retry Worker | Không |
| Archive age / S3 bucket | > 6 tháng; `s3://payment-archive/...` | Bucket credential là secret |
| Kafka topics/consumer groups | `t.payment.events.v2`, `t.payment.webhooks.raw`, `t.order.events` / `payment-order-consumer` | Không |
| PostgreSQL/Redis/Kafka connection | Runtime dependency | Credential là secret |

Tên biến môi trường cụ thể chưa có; không ghi giá trị secret vào tài liệu.

## 3. Quan sát hệ thống

Metrics tối thiểu theo thiết kế cũ:

1. Outbox publish success rate.
2. Webhook processing lag.
3. Refund processing age (`now - refund.created_at`) cho trạng thái `PROCESSING`.
4. DLQ/dead events count.
5. Reconciliation mismatch rate (drift rate).

Bổ sung nên có khi implement: webhook `FAILED_SIGNATURE` rate, optimistic lock
conflict rate, Stripe API latency/error, số transaction `requires_manual_review`,
consumer lag `payment-order-consumer`.

Identifier để truy vết: `correlation_id` (Temporal Workflow ID), `event_id`,
`order_id`, `payment_transaction_id`, `refund_transaction_id`, `stripe_event_id`,
`pi_xxx`/`re_xxx`. Log không được chứa API key, webhook secret hay raw
`client_secret`.

## 4. Cảnh báo

| Alert | Điều kiện | Mức |
|---|---|---|
| Webhook backlog tăng | Consumer lag `t.payment.webhooks.raw` vượt ngưỡng | Cao |
| Refund stuck `PROCESSING` | Refund processing age vượt SLA Stripe | Cao |
| Outbox DEAD/DLQ tăng đột biến | Kafka Connect/DLQ count tăng | Cao |
| Reconciliation mismatch tăng | Drift rate vượt baseline | Cao |
| `FAILED_SIGNATURE` tăng | Sai key/outage/spoofing | Cao |
| `requires_manual_review` tồn đọng | Case chưa xử lý quá ngưỡng | Trung bình |

Ngưỡng cụ thể, owner và runbook link chưa có.

## 5. Failure mode và hành vi suy giảm

| Sự cố | Hành vi |
|---|---|
| PostgreSQL unavailable | Mọi command/transition dừng; webhook endpoint vẫn trả 200 và dump Kafka (không mất event); worker retry với backoff |
| Redis unavailable | Read API/Reconciliation không ảnh hưởng; create/cancel/refund durable ProviderOperation + DB idempotency remain authority; worker không lấy được lock → reject → retry/DLQ, không ignore lock |
| Kafka unavailable | Webhook endpoint không thể dump raw → Stripe sẽ retry (3–5 lần); outbox tích lũy, CDC relay khi khôi phục |
| Stripe API lỗi/timeout | Create/cancel/refund ProviderOperation remains recoverable; replay same provider key or reconcile immutable metadata before FSM decision, never infer a non-capture result |
| Webhook miss (network blip, Stripe outage) | Refund Poller và Reconciliation chốt trạng thái cuối |
| Sai webhook secret | Hàng loạt `FAILED_SIGNATURE`; Auto-Retry Worker replay sau khi sửa key |
| Kafka Connect down | Event không publish; state DB vẫn đúng; replay theo batch sau khi khôi phục |
| Refund fail/reverse sau accept | `requires_manual_review=TRUE`; không tự rút tiền lần hai |

Suy giảm chấp nhận được: kết quả thanh toán đến Order trễ (eventual); refund
accepted nhưng final trễ. Không chấp nhận: transition không qua guard, publish
trước commit, refund vượt hạn mức.

## 6. Scaling và capacity

- API server scale ngang; webhook endpoint nhẹ (chỉ produce Kafka).
- Webhook Processor scale theo partition `t.payment.webhooks.raw`.
- Scanner/Reconciliation/Poller dùng distributed lock; poller lock theo từng
  refund nên nhiều instance được nhưng không trùng.
- Archival job singleton.
- Bottleneck đã biết: `payment_transactions` grow nhanh → partition monthly để
  scanner chỉ quét partition hiện tại.
- Chưa có load test hay capacity assumption.

## 7. Triển khai và rollback

- Thứ tự phụ thuộc: PostgreSQL (migration, partition) → Redis → Kafka topics →
  Debezium connector → Payment service → đăng ký webhook endpoint với Stripe.
- Migration Outbox Polling → CDC: thiết kế cũ ghi đang migrate; connector phải
  route `aggregate_type=Payment` với `partition_key=order_id` trước khi tắt
  polling worker.
- Đổi webhook secret: cập nhật Vault trước, sau đó Auto-Retry replay các
  `FAILED_SIGNATURE` phát sinh trong cửa sổ đổi key.
- Rollback must preserve the published v2 schema; no event is downgraded or republished
  as v1. Schema changes are additive on v2 or require a new version/topic. Do not rollback
  a partition already archived/dropped before restoring it from S3.
- Chưa có canary/feature flag.

## 8. Workers và tác vụ định kỳ

| Worker | Trigger | Input/Output | Lock/idempotency | Chi tiết |
|---|---|---|---|---|
| Outbox CDC Connector (Debezium) | WAL | `outbox_events` → `t.payment.events.v2` | Kafka Connect quản lý retry/DLQ/backoff | Polling Worker cũ deprecated |
| Webhook Processor | `t.payment.webhooks.raw` | Raw webhook → transition + outbox | `ON CONFLICT DO NOTHING` + `stripe_event_id` + `version` | `WF-PAYMENT-002` |
| Payment Expiration Scanner | Mỗi 1–2 phút | `PENDING/PROCESSING` quá hạn → `EXPIRED` + `PAYMENT_EXPIRED` | Distributed lock; batch theo partition hiện tại | `WF-PAYMENT-005` |
| Reconciliation Worker | Hourly | Stripe list ↔ DB → mismatch report + correction transition | Distributed lock; correction idempotent | `WF-PAYMENT-006` |
| Refund Status Poller | 5–15 phút, jitter | `PROCESSING` > 1 phút → `Refund.retrieve` → final | `lock:payment:refund_poll:{id}` | `WF-PAYMENT-007` |
| Webhook Auto-Retry | CronJob | `FAILED_SIGNATURE`/`UNPROCESSED` → re-push Kafka | `retry_count < max_retries`, backoff, giữ ID | `WF-PAYMENT-009` |
| Dead-letter Replay | Manual/on-demand | DLQ message theo filter → replay batch | Re-validate idempotency, rate limit, audit | `WF-PAYMENT-009` |
| Archival CronJob | Chủ nhật 3:00 AM | Partition > 6 tháng → S3 → detach → drop | Singleton; drop chỉ sau verify checksum | `WF-PAYMENT-010` |
| `payment-order-consumer` | `t.order.events` | `ORDER_CANCELLED` → post-command reconciliation marker | Durable inbox `event_id`; never creates primary cancel | `WF-PAYMENT-008` |

## 9. Runbook (rút gọn theo thiết kế cũ)

**Webhook backlog tăng:** kiểm tra consumer lag; scale worker; tạm giảm batch size
nếu DB lock cao.

**Refund stuck ở `PROCESSING`:** chạy poller thủ công theo `order_id`; so khớp
Stripe API (`Refund.retrieve(re_xxx)`) hoặc Stripe Dashboard; nếu cần, đưa vào
manual queue và phát thông báo vận hành.

**Outbox DEAD tăng đột biến:** kiểm tra Kafka availability/topic ACL; replay theo
batch sau khi khôi phục hạ tầng.

**Mismatch reconciliation tăng:** freeze auto-correction cho event type liên quan;
mở chế độ review thủ công đến khi xác định root cause.

**`FAILED_SIGNATURE` hàng loạt:** xác minh `whsec_...` trong Vault khớp Stripe
Dashboard; sau khi sửa, để Auto-Retry Worker replay hoặc replay thủ công.

**Refund reversed / `requires_manual_review`:** CSKH/Kế toán đối chiếu Stripe
Dashboard, quyết định thủ công; không tự động rút tiền lần hai.

Runbook cho cache corruption, DB connection exhaustion, dependency outage đầy đủ
chưa được thiết kế cũ mô tả.
