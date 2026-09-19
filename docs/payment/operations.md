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
| Stripe publishable key (`pk_...`) | Trả cho Order qua initiate/`client-data` | Không |
| KMS/Vault key cho `client_secret_ciphertext` | Mã hóa envelope, rotation theo `client_secret_key_id` | Có |
| `payment_session_ttl` | 15 phút; `expires_at` và TTL Redis (+60s) | Không |
| Webhook timestamp window | 300s | Không |
| Redis lock TTL | 15–30s | Không |
| Expiration Scanner interval | 1–2 phút | Không |
| Reconciliation interval | hourly | Không |
| Refund Poller interval | 5–15 phút, jitter | Không |
| Poller `updated_at` threshold | 1 phút | Không |
| Webhook retry `max_retries`/backoff | Auto-Retry Worker; hết retry với event tài chính → review case | Không |
| Provider-op recovery interval/backoff/`max_attempts` | `WF-PAYMENT-008`; 30–60s, backoff 30s → 5 phút | Không |
| `max_declines_per_attempt` | Mặc định 5; vượt → Payment hủy intent, `FAILED` ([ADR-014](./decisions/ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md)) | Không |
| Danh sách decline code non-retryable | `fraudulent`, `lost_card`, `stolen_card`, `pickup_card`, `restricted_card`, `security_violation`, `merchant_blacklist`, Radar `blocked`; override được qua config | Không |
| `auto_correction_frozen` | Cờ vận hành: Reconciliation chỉ mở review case, không phát `CORR` | Không |
| Retention trimmer | `outbox_events` 15 ngày (dưới CDC watermark), `webhook_logs` 12 tháng, `reconciliation_runs` 12 tháng, `payment_idempotency` theo `expires_at`; batch 5.000 row | Không |
| Kafka topics/consumer groups | `t.payment.events.v2`, `t.payment.webhooks.raw` | Không |
| PostgreSQL/Redis/Kafka connection | Runtime dependency | Credential là secret |

Tên biến môi trường cụ thể chưa có; không ghi giá trị secret vào tài liệu.

## 3. Quan sát hệ thống

Metrics tối thiểu theo thiết kế cũ:

1. Outbox publish success rate.
2. Webhook processing lag.
3. Refund processing age (`now - refund.created_at`) cho trạng thái `PROCESSING`.
4. DLQ/dead events count.
5. Reconciliation mismatch rate (drift rate).

Bổ sung nên có khi implement: webhook `FAILED_SIGNATURE`/`FAILED_PROCESSING` rate,
optimistic lock conflict rate, Stripe API latency/error, số `payment_review_cases`
đang mở theo `reason_code`, tuổi `provider_operations` `UNKNOWN`/`REQUESTED` lâu
nhất, decline rate và tỷ lệ attempt `FAILED` do `DECLINE_LIMIT_EXCEEDED`, kích thước
partial index và thời gian chạy scanner theo tổng row.

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
| Review case tồn đọng | `payment_review_cases` `OPEN` quá ngưỡng tuổi/số lượng, theo `reason_code` | Trung bình (Cao với `CAPTURE_AFTER_CANCEL`, `REFUND_REVERSED`) |
| Provider operation kẹt | `UNKNOWN`/`REQUESTED` quá `max_attempts` hoặc tuổi > 10 phút | Cao |
| Decline rate bất thường | Tỷ lệ `T3a`/initiate vượt baseline (fraud, lỗi tích hợp Elements) | Trung bình |

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
| Refund fail/reverse sau accept | Review case `REFUND_FAILED_AFTER_ACCEPT`/`REFUND_REVERSED`; không tự tạo refund thứ hai; CSKH hoàn tay và đóng case |
| KMS/Vault key không giải mã được | `client-data` trả `usable=false`; buyer không thể tiếp tục phiên → Order mở attempt mới sau khi phiên hết hạn; alert Cao |

Suy giảm chấp nhận được: kết quả thanh toán đến Order trễ (eventual); refund
accepted nhưng final trễ. Không chấp nhận: transition không qua guard, publish
trước commit, refund vượt hạn mức.

## 6. Scaling và capacity

- API server scale ngang; webhook endpoint nhẹ (chỉ produce Kafka).
- Webhook Processor scale theo partition `t.payment.webhooks.raw`.
- Scanner/Reconciliation/Poller dùng distributed lock; poller lock theo từng
  refund nên nhiều instance được nhưng không trùng.
- Reconciliation và retention trimmer singleton.
- `payment_transactions`/`payment_status_history` tăng đơn điệu (không partition/
  archive, [ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md));
  scanner/poller/recovery dựa vào partial index theo trạng thái hoạt động nên không
  phụ thuộc tổng row. Theo dõi bloat/autovacuum và kích thước index.
- Chưa có load test hay capacity assumption.

## 7. Triển khai và rollback

- Thứ tự phụ thuộc: PostgreSQL (migration) → KMS/Vault key → Redis → Kafka topics →
  Debezium connector → Payment service → đăng ký webhook endpoint với Stripe.
- Migration Outbox Polling → CDC: thiết kế cũ ghi đang migrate; connector phải
  route `aggregate_type=Payment` với `partition_key=order_id` trước khi tắt
  polling worker.
- Đổi webhook secret: cập nhật Vault trước, sau đó Auto-Retry replay các
  `FAILED_SIGNATURE` phát sinh trong cửa sổ đổi key.
- Rollback must preserve the published v2 schema; no event is downgraded or republished
  as v1. Schema changes are additive on v2 or require a new version/topic. Rollback
  migration không được xóa `provider_operations`/`payment_review_cases` đang mở.
- Chưa có canary/feature flag.

## 8. Workers và tác vụ định kỳ

| Worker | Trigger | Input/Output | Lock/idempotency | Chi tiết |
|---|---|---|---|---|
| Outbox CDC Connector (Debezium) | WAL | `outbox_events` → `t.payment.events.v2` | Kafka Connect quản lý retry/DLQ/backoff | Polling Worker cũ deprecated |
| Webhook Processor | `t.payment.webhooks.raw` | Raw webhook → transition + outbox | `ON CONFLICT DO NOTHING` + `stripe_event_id` + `version` | `WF-PAYMENT-002` |
| Payment Expiration Scanner | Mỗi 1–2 phút | `PENDING` quá hạn → `CANCEL` operation → `PaymentIntent.cancel` → `EXPIRED` + `PAYMENT_EXPIRED` (hoặc `SUCCESS` nếu capture thắng) | `SKIP LOCKED` + partial index; lock theo payment | `WF-PAYMENT-005` |
| Provider-operation Recovery | Mỗi 30–60s | `REQUESTED`/`UNKNOWN` quá `next_retry_at` → replay cùng key → bind hoặc review case | Key bất biến; backoff; `max_attempts` | `WF-PAYMENT-008` |
| Reconciliation Worker | Hourly | Stripe retrieve ↔ DB → `reconciliation_runs` + `CORR` (khi không freeze) + review case | Singleton lock; correction idempotent | `WF-PAYMENT-006` |
| Refund Status Poller | 5–15 phút, jitter | `PROCESSING` > 1 phút → `Refund.retrieve` → final | `payment:lock:{payment_transaction_id}` | `WF-PAYMENT-007` |
| Webhook Auto-Retry | CronJob | `FAILED_SIGNATURE`/`UNPROCESSED`/`FAILED_PROCESSING` → re-verify + re-push Kafka | `retry_count < max_retries`, `next_retry_at`, dedup `stripe_event_id` | `WF-PAYMENT-009` |
| Dead-letter Replay | Manual/on-demand | DLQ message theo filter → replay batch | Re-validate idempotency, rate limit, audit | `WF-PAYMENT-009` |
| Retention Trimmer | Hàng ngày ngoài giờ cao điểm | Xóa `outbox_events` dưới CDC watermark > 15 ngày, `webhook_logs` đã xử lý > 12 tháng, `reconciliation_runs` > 12 tháng, `payment_idempotency` hết hạn | Singleton; batch 5.000 row | `data.md §7` |

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

**Review case (`payment_review_cases`):** nhận case theo `reason_code`; đối chiếu
Stripe Dashboard, `payment_status_history`, `webhook_logs.raw_body`; đóng với
`resolution_type` bắt buộc (`WF-PAYMENT-010`). Với `REFUND_FAILED_AFTER_ACCEPT`/
`REFUND_REVERSED`: hoàn tiền tay và đóng `MANUAL_REFUND_OUTSIDE_SYSTEM`; không bao giờ
tạo refund thứ hai trong hệ thống. Với `CAPTURE_AFTER_CANCEL`: báo Order đi lane hậu
thanh toán.

**Provider operation kẹt `UNKNOWN`:** kiểm tra Stripe Dashboard theo metadata
`payment_transaction_id`; nếu tìm thấy đúng một object, bind thủ công qua correction
(`MANUAL`, `actor`); nếu không, đóng case `NO_ACTION_NEEDED` và để Order mở attempt mới.

Runbook cho cache corruption, DB connection exhaustion, dependency outage đầy đủ
chưa được thiết kế cũ mô tả.
