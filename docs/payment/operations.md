---
doc_id: payment-operations
service: payment
scope: target
status: implemented
owners:
  - Payment Service team
evidence:
  - java/services/payment-service/src/main/resources/application.yml
  - java/services/payment-service/src/main/java/com/platform/paymentservice/adapter/in/scheduler
  - java/services/payment-service/src/main/java/com/platform/paymentservice/adapter/in/http/PaymentOperationsController.java
verified_against_commit: null
---

# Payment Service — Vận hành

> **Đối chiếu runtime 2026-10-11:** xem [phạm vi hiện tại và năng lực chưa triển khai](./runtime-status.md)
> và [ADR-023](./decisions/ADR-023-reconciliation-ownership-and-audit-corrections.md). Các mục tiêu KMS, metric nghiệp vụ,
> heartbeat, health/DR/export và RBAC bên ngoài không phải bằng chứng đã được runtime hiện thực.

> Acceptance criteria vận hành cho Payment Service: SLO, cấu hình, metric, alert,
> failure mode, triển khai, worker và runbook. Runtime/worker đã có; dashboard và alert
> rule hạ tầng chưa nằm trong module này. Mọi con số SLO là **mục tiêu thiết kế** và phải được đo lại
> sau load test trước khi thành cam kết. Tên metric/property dùng quy ước đề xuất
> (`payment.*` cho property, `payment_*` cho metric) để implementation và dashboard
> thống nhất từ đầu.

## 1. SLO, SLI và health

### 1.1. SLI/SLO đề xuất

| SLI | Cách đo | SLO đề xuất | Lý do ngưỡng |
|---|---|---|---|
| Initiate availability | `2xx + 409 + 422` / tổng `POST /v1/payments/initiate` (loại `503` do Stripe) | ≥ 99.9% / 30 ngày | Initiate lỗi = buyer không thanh toán được |
| Initiate latency | p95 từ request tới response `PENDING` (gồm `PaymentIntent.create`) | p95 ≤ 1.5s, p99 ≤ 3s | Stripe p95 ~300–600ms + 2 DB transaction |
| Webhook endpoint | tỷ lệ `2xx` và p99 latency của `POST /v1/payments/webhook/stripe` | ≥ 99.95% `2xx`; p99 ≤ 500ms | Stripe coi > 5s là fail và retry; 500ms là biên an toàn |
| Webhook-to-fact latency | `processed_at − received_at` của `webhook_logs` `PROCESSED` | p95 ≤ 30s, p99 ≤ 2 phút | Order chờ `PAYMENT_SUCCESS` để tiếp saga |
| Outbox publish latency | thời điểm Kafka record − `outbox_events.created_at` | p99 ≤ 60s | Debezium CDC gần real-time; > 60s là connector có vấn đề |
| Financial fact correctness | số review case `AMOUNT_MISMATCH` + `CAPTURE_AFTER_CANCEL` + drift do lỗi hệ thống / tổng payment `SUCCESS` | ≤ 0.01% / 30 ngày | Đây là SLI "tiền đúng"; vi phạm là incident dù không có downtime |
| Refund acceptance latency | p95 `accepted_at − created_at` của `refund_transactions` | p95 ≤ 5s | Bao gồm `Refund.create` |
| Refund finalization | tỷ lệ refund rời `PROCESSING` trong 7 ngày | ≥ 99% | Issuing bank có thể mất 5–10 ngày; > 7 ngày là bất thường cần poller/reconciliation |
| Recovery completeness | `provider_operations` rời `UNKNOWN`/`REQUESTED` trong 10 phút | ≥ 99.9% | Sau 10 phút Order vẫn kẹt `INITIATING`/`CANCEL_PENDING` |
| Ledger correctness | journal không cân bằng hoặc duplicate provider movement | 0 | Bất kỳ vi phạm nào là P1 và financial writes fail closed |
| Dispute deadline coverage | dispute active có owner trước `evidence_due_at - 24h` | 100% | Trễ evidence có thể làm mất tiền tự động |
| Balance reconciliation | balance cursor hoàn tất và totals khớp | ≤ 0.01% drift; lag < 2h | Bắt movement fee/payout/dispute ngoài object FSM |
| Review case time-to-resolve | p90 `resolved_at − opened_at` | p90 ≤ 2 ngày làm việc (Cao: 4 giờ) | Tiền của khách đang treo |

Error budget theo tháng; khi cạn budget của "Financial fact correctness" thì đóng
băng thay đổi FSM/webhook cho tới khi có RCA.

### 1.2. Health

**Hiện trạng runtime:** Actuator liveness/readiness đã có. Bảng dưới là mục tiêu
probe theo vai trò; chưa có custom HealthIndicator kiểm tra đủ Stripe/KMS/Kafka/CDC
hoặc route `/internal/health/deps`. `UP` chưa chứng minh key hợp lệ với Stripe hay
Order nhận payment fact. Local gate dùng [checkout runbook](../frontend/checkout-local-runbook.md)
và kiểm tra connector/tasks cùng giao dịch Sandbox thật. Không dựa vào endpoint
thiết kế chưa có để đánh dấu runtime đạt.

| Probe | Kiểm tra | Ghi chú |
|---|---|---|
| Liveness | Process + event loop | Không gọi dependency |
| Readiness — API command (`initiate`/`cancel`/`refund`) | PostgreSQL `SELECT 1` trong pool; KMS/Vault key `client_secret` load được; Stripe secret key đã nạp (không gọi Stripe) | Redis **không** chặn readiness (fallback DB) |
| Readiness — Webhook endpoint | PostgreSQL inbox + KMS encryption + Kafka producer/topic | Thiếu một dependency phải fail readiness và trả `503`; không nhận `200` nếu chưa durable |
| Readiness — Webhook Processor / scheduler | PostgreSQL + Kafka consumer group assigned + Stripe key | Báo riêng, không kéo API instance ra khỏi LB |
| Startup | Migration đã ở version kỳ vọng; config bắt buộc có mặt; `whsec_` decode được | Fail-fast nếu thiếu `sk_`/`whsec_`/KMS key |
| Dependency check nội bộ (`/internal/health/deps`) | Trạng thái từng dependency + `auto_correction_frozen` + version connector Debezium | Chỉ cho vận hành, không dùng cho LB |

## 2. Cấu hình cần công bố

Bảng có cả property runtime và cấu hình dự trù; mục chưa bind không có tác dụng trong code hiện tại. Property naming `payment.<nhóm>.<tên>`; giá trị secret chỉ qua Vault/Secret
Manager, **không** commit và không log. Đổi property có đánh dấu "restart" cần
rolling restart. Runtime hiện tại nạp env/config lúc startup, chưa có hot reload;
local Compose phải recreate container khi đổi env. Các ô reload trong bảng dưới
là khả năng thiết kế, chưa phải bằng chứng runtime.

### 2.1. Stripe

| Property | Mục đích | Giá trị thiết kế | Secret | Restart |
|---|---|---|---|---|
| `payment.stripe.secret-key` | `StripeClient` singleton | `sk_test_…` (dev/staging), `sk_live_…` (prod) | Có | Có |
| `payment.stripe.publishable-key` | Trả cho Order qua initiate/`client-data` | `pk_…` cùng sandbox với secret key | Không | Có (runtime hiện tại) |
| `payment.stripe.webhook-secrets` | Verify `Stripe-Signature`; **danh sách** (tối đa 2) để rotation không gián đoạn | `whsec_…` | Có | Có (runtime hiện tại) |
| `payment.stripe.api-version` | Pin API version cho SDK và webhook payload | `2026-08-26.dahlia` (ADR-021) | Không | Có |
| `payment.stripe.timeout.connect/read` | HTTP timeout SDK | 5s / 15s | Không | Không |
| `payment.stripe.max-network-retries` | Retry của SDK cho lỗi mạng (idempotent nhờ key) | 2 | Không | Không |
| `payment.stripe.webhook-window-seconds` | Cửa sổ `\|now − t\|` | 300 | Không | Không |
| `payment.stripe.webhook-max-bytes` | Giới hạn exact request bytes | 262144 | Không | Không |
| `payment.provider.safe-replay-horizon` | Same-key provider retry trước horizon | `PT23H` | Không | Không |
| `payment.provider.cancel-recovery-window` / `refund-recovery-window` | Deadline phục hồi operation cancel/refund | `P7D` / `P7D` | Không | Không |
| `payment.stripe.decline.non-retryable-codes` (chưa bind) | Policy `RULE-PAY-014`; runtime dùng `DeclinePolicy` hardcoded | `fraudulent,lost_card,stolen_card,pickup_card,restricted_card,security_violation,merchant_blacklist` + Radar `blocked` | Không | Không |
| `payment.stripe.decline.max-per-attempt` | `max_declines_per_attempt` | 5 | Không | Không |
| `payment.stripe.payment-method-types` | Phạm vi phương thức thanh toán của PaymentIntent (`RULE-PAY-025`, [ADR-022](./decisions/ADR-022-refund-lifecycle-hardening.md)); Stripe Dashboard payment-method configuration phải khớp | `card` | Không | Có |

Webhook endpoint đăng ký trên Stripe Dashboard/CLI phải subscribe đúng tập event:
`payment_intent.processing`, `payment_intent.succeeded`,
`payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`,
`refund.updated`, `refund.failed`, `charge.dispute.created`, `charge.dispute.updated`,
`charge.dispute.closed`, `charge.dispute.funds_withdrawn`,
`charge.dispute.funds_reinstated`. Đây là 12 canonical event; không đăng ký
`charge.refund.updated`. Event khác bị
`IGNORED` — subscribe thừa chỉ tốn tài nguyên, thiếu thì mất fact.

### 2.2. Phiên, khóa, mã hóa

| Property | Mục đích | Giá trị thiết kế | Secret | Restart |
|---|---|---|---|---|
| `payment.session.ttl` | `expires_at = created_at + ttl` | 15 phút | Không | Không |
| `payment.session.client-secret-margin` | `client_expires_at = expires_at − margin` | 30s | Không | Không |
| `payment.crypto.kms-key-id` (chưa triển khai) | Envelope key cho `client_secret_ciphertext`; ghi vào `client_secret_key_id` | Theo môi trường | Có | Có |
| `payment.crypto.previous-kms-key-ids` (chưa triển khai) | Giải mã row cũ trong cửa sổ rotation | Danh sách | Có | Không |
| `payment.crypto.local-key` | Runtime hiện tại: AES-256 key cố định; chưa có guard cấm profile `prod`. Giới hạn dùng dev/test là yêu cầu triển khai, không phải kiểm tra đã có trong code | base64 32 byte | Có (local) | Có |
| `payment.command.lease-ttl` / `heartbeat` | DB execution lease/fencing đã có; heartbeat dự trù chưa được worker gọi | 45s / 10s | Không | Không |
| `payment.webhook.lease-ttl` / `heartbeat` | Durable inbox claim lease đã có; heartbeat dự trù chưa được worker gọi | 60s / 15s | Không | Không |

### 2.3. Worker và retention

| Property | Mục đích | Giá trị thiết kế |
|---|---|---|
| `payment.scheduling.enabled` | Bật đăng ký toàn bộ `@Scheduled` workers; chỉ tắt trong context test hoặc instance API-only | `true` |
| `payment.scheduler.expiration.interval` / `batch-size` | Expiration Scanner | 60s / 200 |
| `payment.scheduler.recovery.interval` / `backoff` / `max-backoff` / `max-attempts` / `batch-size` | Provider-operation Recovery | `PT30S` / `PT30S` / `PT5M` / 20 / 100 |
| `payment.scheduler.refund.interval` / `minimum-age` / `batch-size` | Refund Status Poller | `PT10M` / `PT1M` / 100 |
| `payment.reconciliation.interval` / `lookback` / `batch-size` | Hai lane reconciliation | `PT1H` / `PT65M` / 500 |
| `payment.reconciliation.refund-success-lookback` | Refund `SUCCESS` được `Refund.retrieve` lại từ `finalized_at` để bắt reversal khi webhook miss (`RULE-PAY-024`); Stripe cho phép tiền quay về tới ~30 ngày | `P35D` |
| `payment.reconciliation.auto-correction-frozen` | Default trước control row đầu tiên; Finance Ops API ghi freeze bền vững vào `reconciliation_runs` lane `CONTROL` | `false` |
| `payment.webhook.retry.max` / `base-delay` / `interval` / `batch-size` | Auto-Retry Worker | 8 / `PT30S` / `PT30S` / 100 |
| `payment.review.idempotency-retention` | Replay window cho claim/resolve/correction/start/freeze | `P7D` |
| `payment.retention.cron` / `batch-size` / `outbox-slot-name` | Lịch, batch và đúng logical slot cho Trimmer | `0 30 3 * * *` / 5.000 / `payment_outbox_slot` |
| `payment.retention.outbox-days` / `webhook-raw-days` / `webhook-metadata-months` / `financial-years` / `reconciliation-run-months` | Retention Trimmer (`data.md §7`) | 15 / 90 / 12 / 7 / 12 |

### 2.4. Hạ tầng

PostgreSQL DSN/pool (đề xuất pool 20/instance, `statement_timeout` 5s cho API, 60s
cho worker), Kafka bootstrap/security và hai topic. Payment Gate 3 không kết nối
Redis; nếu bổ sung accelerator sau này phải giữ PostgreSQL là authority:

- `t.payment.events.v2`: 6 partition, replication 3, retention tối thiểu 15 ngày,
  max message 64 KiB, classification `INTERNAL`; chỉ Debezium được produce.
- `t.payment.webhooks.raw`: 6 partition, replication 3, retention 7 ngày, max
  message 384 KiB (đủ raw body 256 KiB sau Base64 + CloudEvent envelope),
  classification `CONFIDENTIAL`; ACL chỉ cho Payment API/worker.

Debezium connector dùng `table.include.list=public.outbox_events`, outbox SMT route
theo `aggregate_type`, key `partition_key` và chuyển `payload` full structured
CloudEvent nguyên vẹn thành Kafka value. Cột `headers` chỉ mirror metadata cho
observability, không phải wire authority ([ADR-015](./decisions/ADR-015-canonical-asyncapi-and-structured-cloudevents.md)).
Credential là secret.
Connector cấu hình không phát DELETE/tombstone từ cleanup. Trimmer chỉ xóa outbox
khi `commit_lsn` đã dưới `confirmed_flush_lsn` của đúng replication slot và connector healthy.

## 3. Quan sát hệ thống

### 3.1. Metric

Danh mục dưới là target; runtime chưa emit metric nghiệp vụ `payment_*`. Dashboard/alert phụ thuộc chúng chưa được kiểm chứng.

Quy ước: Micrometer/Prometheus, prefix `payment_`; **không** label bằng
`order_id`/`payment_transaction_id`/`user_id` (cardinality + PII); label cho phép:
`route`, `status`, `event_type`, `transition_id`, `reason_code`, `operation_type`,
`outcome`, `worker`.

| Metric | Kiểu | Label | Ý nghĩa / SLI liên quan |
|---|---|---|---|
| `payment_http_requests_total`, `payment_http_request_duration_seconds` | counter, histogram | `route`, `status` | Initiate availability/latency |
| `payment_webhook_received_total` | counter | `outcome` (`accepted`, `auth_failed`, `payload_rejected`, `inbox_error`, `kafka_error`, `duplicate_terminal`) | Endpoint/auth availability |
| `payment_webhook_processing_duration_seconds` | histogram | `event_type`, `outcome` (`PROCESSED`, `IGNORED`, `RETRY_WAIT`, `DEAD`) | Webhook-to-fact |
| `payment_webhook_lag_seconds` | gauge | — | `now − received_at` của message cũ nhất chưa `PROCESSED` |
| `payment_webhook_signature_failures_total` | counter | — | Sai key/spoof |
| `payment_fsm_transitions_total` | counter | `transition_id`, `triggered_by` | Sức khỏe FSM; `T3a` là decline rate |
| `payment_fsm_invalid_transitions_total` | counter | `from`, `event` | Phải ~0 |
| `payment_optimistic_lock_conflicts_total` | counter | `aggregate` | Cao bất thường = webhook/poller giẫm nhau |
| `payment_outbox_pending_age_seconds` | gauge | — | `now − min(created_at)` của outbox chưa qua CDC watermark |
| `payment_outbox_events_total` | counter | `event_type` | Đối chiếu với Kafka record count |
| `payment_provider_request_duration_seconds` | histogram | `operation_type`, `outcome` (`ok`, `rejected`, `timeout`, `error`) | Stripe latency/error |
| `payment_provider_operations_pending` | gauge | `status` (`REQUESTED`, `UNKNOWN`), `operation_type` | Recovery backlog |
| `payment_provider_operations_oldest_age_seconds` | gauge | `status` | SLI recovery completeness |
| `payment_provider_horizon_exceeded_total`, `payment_lease_takeovers_total`, `payment_fencing_rejections_total` | counter | `operation_type`/`worker` | Blind-retry/stale-worker guard |
| `payment_refund_processing_age_seconds` | gauge | — | Tuổi refund `PROCESSING` cũ nhất |
| `payment_refund_outcomes_total` | counter | `outcome` (`SUCCESS`, `FAILED`, `REVERSED`) | Reversal rate |
| `payment_review_cases_open` | gauge | `reason_code` | Backlog thủ công |
| `payment_review_case_age_seconds` | gauge | `reason_code` | Case mở lâu nhất |
| `payment_reconciliation_runs_total`, `payment_reconciliation_drift_total`, `payment_reconciliation_corrections_total` | counter | `outcome` / `reason` | Drift rate = drift / scanned |
| `payment_reconciliation_frozen` | gauge (0/1) | — | Cờ freeze đang bật |
| `payment_ledger_journals_total`, `payment_ledger_imbalance_rejections_total`, `payment_balance_reconciliation_drift_minor` | counter/gauge | `journal_type`, `currency` | Subledger correctness |
| `payment_disputes_open`, `payment_dispute_deadline_seconds` | gauge | `status` | Chargeback exposure/deadline |
| `payment_scheduler_run_duration_seconds`, `payment_scheduler_batch_size` | histogram, gauge | `worker` | Scanner/poller/trimmer có chậm theo tổng row không |
| `payment_trimmer_deleted_rows_total` | counter | `table` | Retention hoạt động |
| `payment_client_secret_decrypt_total` | counter | `outcome` (`usable`, `not_usable`, `error`) | Lỗi KMS lộ ở đây |
| `payment_db_pool_*`, `kafka_consumer_lag`, JVM/GC | chuẩn | — | Hạ tầng |

### 3.2. Log và trace

- Log JSON có `correlation_id` (Temporal Workflow ID từ Order, lan qua header
  `X-Correlation-Id`), `order_id`, `payment_transaction_id`, `refund_transaction_id`,
  `stripe_event_id`, `provider_object_id`, `transition_id`, `operation_id`.
- **Cấm** log: `sk_`, `whsec_`, `client_secret` plaintext/ciphertext, `raw_body`
  webhook đầy đủ (chỉ log `stripe_event_id` + `event_type` + `raw_body_sha256`),
  `billing_details`.
- Trace: span cho HTTP command, mỗi DB transaction, mỗi Stripe call (tag
  `operation_type`, `provider_idempotency_key` hash), Kafka produce/consume;
  `traceparent` được ghi vào `outbox_events.headers` để Order nối trace.
- Mọi log `WARN`+ liên quan financial fact phải chứa `transition_id` hoặc
  `reason_code` để dashboard nhóm được.

### 3.3. Dashboard tối thiểu

1. **Money path:** initiate rate/latency, `T2` rate, decline rate (`T3a`),
   `FAILED`/`EXPIRED`/`CANCELED` rate, webhook-to-fact latency.
2. **Async health:** webhook lag, outbox age, consumer lag, connector state.
3. **Recovery & review:** provider ops/horizon/lease, review cases, refund age.
4. **Money control:** ledger journals/imbalance, balance drift, dispute deadline/funds.
5. **Dependencies:** Stripe latency/error theo `operation_type`, DB pool, Kafka và
   KMS decrypt errors.

## 4. Cảnh báo

Mức: **P1** (gọi ngay, tiền/fact sai hoặc buyer không thanh toán được), **P2** (xử
lý trong giờ làm), **P3** (ticket). Mọi alert link tới runbook §9.

| Alert | Điều kiện (đề xuất) | Mức | Runbook |
|---|---|---|---|
| Initiate error rate | `5xx`/tổng > 2% trong 5 phút, hoặc `503 PROVIDER_OUTCOME_UNKNOWN` > 5% | P1 | §9.1 |
| Webhook endpoint không nhận | `payment_webhook_received_total{outcome="kafka_error"}` > 0 trong 2 phút, hoặc endpoint `5xx` | P1 | §9.2 |
| Webhook lag | `payment_webhook_lag_seconds` > 120 trong 5 phút | P1 | §9.2 |
| Outbox không publish | `payment_outbox_pending_age_seconds` > 120 | P1 | §9.3 |
| Fact sai | `payment_fsm_invalid_transitions_total` tăng, hoặc review case `AMOUNT_MISMATCH`/`CAPTURE_AFTER_CANCEL` mới | P1 | §9.7 |
| Provider operation kẹt | `payment_provider_operations_oldest_age_seconds{status="UNKNOWN"}` > 600, hoặc pending > 50 | P1 | §9.4 |
| Stripe lỗi | `outcome="error"` + `timeout` > 10% trong 5 phút | P1 | §9.5 |
| Webhook authentication fail hàng loạt | > 10 trong 5 phút | P1 (sai key) / P2 (spoof lẻ tẻ) | §9.6 |
| KMS decrypt lỗi | `payment_client_secret_decrypt_total{outcome="error"}` > 0 | P1 | §9.8 |
| Refund `pending` lâu | `payment_refund_processing_age_seconds` > 24 giờ (nghi số dư Stripe khả dụng không đủ) | P3 | §9.9 |
| Refund stuck | `payment_refund_processing_age_seconds` > 7 ngày | P2 | §9.9 |
| Reversal rate | `REVERSED` / `SUCCESS` refund > 1% / 7 ngày | P2 | §9.7 |
| Review case backlog | `payment_review_cases_open` > 20, hoặc age > 4h với `reason_code` ∈ {`CAPTURE_AFTER_CANCEL`, `REFUND_REVERSED`} | P2 | §9.7 |
| Reconciliation drift | drift/scanned > 0.5% một run, hoặc 2 run liên tiếp `FAILED` | P2 | §9.10 |
| Reconciliation frozen quá lâu | `payment_reconciliation_frozen == 1` > 24h | P3 | §9.10 |
| Decline rate bất thường | `T3a`/initiate > baseline ×2 trong 30 phút | P2 (fraud/tích hợp Elements) | §9.11 |
| Optimistic conflict | conflicts/transitions > 5% | P3 | §9.2 |
| Scheduler chậm | `payment_scheduler_run_duration_seconds` p95 > interval/2 | P3 | §9.12 |
| Trimmer không chạy | `payment_trimmer_deleted_rows_total` không tăng 48h hoặc `payment_outbox_pending_age_seconds` tăng đều | P3 | §9.12 |
| Ledger imbalance | `payment_ledger_imbalance_rejections_total` tăng hoặc balance drift vượt 0.01% | P1 | §9.10 |
| Dispute deadline | `payment_dispute_deadline_seconds < 86400` và chưa assigned | P1 | §9.7 |

## 5. Failure mode và hành vi suy giảm

| Sự cố | Phát hiện | Tác động | Hành vi hệ thống | Khôi phục |
|---|---|---|---|---|
| PostgreSQL unavailable | API/readiness fail | Không command; buyer đang ở Elements vẫn có thể hoàn tất trên Stripe | Webhook ingress trả `503` vì không thể persist inbox; Stripe retry delivery mới. Processor/scheduler dừng | DB lên → Stripe retry + reconciliation; không trả `200` giả |
| PostgreSQL chậm/lock | Latency, pool timeout | Initiate p95 vượt SLO | `statement_timeout` cắt; scanner giảm batch | Tuning; xem §9.12 |
| Redis unavailable | Hạ tầng chung báo lỗi | Không có với Payment Gate 3 | Payment không có Redis read/write path; DB idempotency/lease vẫn hoạt động | Khôi phục độc lập, không cần chuyển mode Payment |
| Kafka unavailable | Endpoint `kafka_error` | Stripe nhận `5xx` → **Stripe retry** 3–5 lần trong ~3 ngày (không mất) | Outbox tích lũy trong DB; CDC relay sau | Kafka lên → Debezium bắt kịp; nếu Stripe đã hết retry, replay từ Stripe Dashboard ("Resend") hoặc reconciliation |
| Debezium/Kafka Connect down | Outbox age | Order không nhận fact (saga chờ) | State DB đúng; outbox không bị trimmer xóa vì dưới watermark | Restart connector; **không** publish tay |
| Stripe API lỗi/timeout | Provider error rate | Initiate/refund `503`; cancel pending | Operation `UNKNOWN`; trước horizon replay same-key, sau horizon lookup-only; không suy luận | Stripe hồi → recovery bind/review |
| Stripe rate limit (429) | Provider `error` với code 429 | Như trên | SDK retry có backoff; scheduler giảm batch | Giảm interval scanner/poller tạm thời |
| Webhook miss (Stripe outage/network) | Refund age, reconciliation drift | Fact đến trễ | Poller (refund `PROCESSING`) và reconciliation (payment; refund `SUCCESS` trong 35 ngày) chốt từ `retrieve` | Không cần thao tác |
| Sai/đổi `whsec_` | ingress `auth_failed` spike | Delivery bị `400`, không có inbox row | Giữ secret cũ+mới overlap; Stripe retry delivery mới với signature mới | Sửa secret, dùng Stripe Resend nếu cần; reconciliation lấp gap |
| KMS/Vault không giải mã được | decrypt `error` | Buyer reload trang không lấy được secret; initiate mới vẫn mã hóa được (key hiện hành) hoặc fail nếu key hiện hành hỏng | `client-data` trả `usable=false`; phiên hết hạn → Order mở attempt mới | Khôi phục local key hiện hữu; previous-kms-key-ids chưa có runtime adapter |
| Refund fail/reverse sau accept | Review case | Khách chưa nhận tiền | Không tạo refund thứ hai; case `OPEN` | Hoàn tay, đóng case |
| Capture sau cancel (race) | Review case `CAPTURE_AFTER_CANCEL` | Order đã nhả hàng nhưng tiền đã thu | Payment `SUCCESS`; Order lane hậu thanh toán/financial reconciliation | Order/CSKH quyết định giao lại hoặc refund qua lane refund |
| Dispute/chargeback | dispute metrics/review + facts | Stripe giữ tiền + phí | Dispute FSM, journal funds movement, deadline alert; Payment status không đổi | Finance Ops nộp evidence, reconcile outcome/funds |
| Ledger/balance drift | reconciliation/imbalance alert | Báo cáo tiền sai | Reject unbalanced transition; freeze correction, financial writes fail closed nếu invariant DB hỏng | RCA, reversal journal + rerun balance lane |
| Drift tăng đột biến | Reconciliation | Có thể do bug transition | Bật `auto-correction-frozen`; chỉ mở case | RCA rồi unfreeze |

Suy giảm chấp nhận được: fact đến Order trễ; refund accepted nhưng final trễ;
`client-data` tạm không dùng được. **Không** chấp nhận: transition không qua guard,
publish trước commit, refund thứ hai/partial incremental, terminal non-capture khi intent còn sống, mất
webhook đã nhận `200`.

## 6. Scaling và capacity

Giả định ban đầu (cần load test xác nhận): 50 initiate/s peak, 1 payment ≈ 4–6
webhook, 1 order ≈ 1.3 attempt, refund ≈ 2% order.

| Thành phần | Scale | Giới hạn |
|---|---|---|
| API server | Ngang, stateless; 2+ instance | Pool DB 20/instance; Stripe rate limit (~100 req/s live mặc định) |
| Webhook endpoint | Cùng process API hoặc tách | Verify HMAC + inbox encrypt/write + Kafka producer |
| Webhook Processor | Theo partition (6) + DB Retry Worker | Correctness bằng inbox lease/checkpoint, không dựa partition order |
| Expiration Scanner | Nhiều instance, `SKIP LOCKED`, batch 200 | Mỗi row = 1 Stripe cancel → ≤ 200 call/phút/instance |
| Recovery / Poller | Nhiều instance, lock theo payment | Stripe rate limit |
| Reconciliation, Trimmer | PostgreSQL singleton: session advisory lock + run-row fencing + partial unique `RUNNING`/lane cho reconciliation; transaction advisory lock cho Trimmer | Object batch 500 mỗi nhóm; balance page ≤100, đọc hết cửa sổ và đọc bù downtime; Redis không cấp correctness |
| PostgreSQL | Một primary + replica cho read `GET`/dashboard | Bảng tăng đơn điệu ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)); partial index giữ tập hoạt động nhỏ; theo dõi bloat, `autovacuum` cho `payment_status_history`/`webhook_logs` |

Ước lượng dung lượng phải load-test cả journal/dispute. Raw webhook ciphertext
~3–8 KB/row chỉ giữ 90 ngày; metadata 12 tháng; chứng từ/journal giữ 7 năm.

## 7. Triển khai và rollback

### 7.1. Thứ tự lần đầu

1. PostgreSQL: migration tạo 12 bảng (`development.md §8`), deferred ledger
   balance trigger và quyền deny update/delete ledger; user app chỉ có DML +
   `SELECT` trên `outbox_events` cho Debezium user (REPLICATION).
2. Runtime demo/staging: nạp `sk_`, `whsec_` và `payment.crypto.local-key`/`local-key-id` (AES-256). Production KMS adapter chưa triển khai; không dùng các property KMS dự trù như cấu hình đang hoạt động.
3. Kafka topic (`t.payment.webhooks.raw` 6 partition retention 7 ngày;
   `t.payment.events.v2` theo platform), consumer group.
4. Debezium connector với outbox SMT; xác nhận `aggregate_type=Payment` route đúng
   topic và key = `partition_key`.
5. Deploy Payment (API + processor + scheduler) với `auto-correction-frozen=true`
   cho tuần đầu.
6. Smoke: initiate test mode → `PENDING`; `client-data` → `usable=true`.
7. Đăng ký webhook endpoint trên Stripe (đúng tập event §2.1), lấy `whsec_` → nạp
   Vault → restart/recreate runtime; trigger chỉ smoke fixture riêng. Gate mua hàng
   phải dùng PaymentIntent của đơn Checkout và đối chiếu Order PAID.
8. Bật Order gọi Payment; theo dõi dashboard 1–3 ngày rồi unfreeze.

### 7.2. Rolling deploy

- **Lần nâng cấp ADR-023:** dừng reconciliation worker cũ trước khi chạy bản mới; cần session pooling/direct PostgreSQL. Xem `runtime-status.md` phần triển khai ownership.
- API và processor deploy rolling; worker mới và cũ chạy song song an toàn nhờ
  optimistic lock + unique constraint + `SKIP LOCKED`.
- Migration **expand/contract**: thêm cột/index nullable trước, code sau, xóa cột ở
  release kế. Index trên bảng lớn tạo `CONCURRENTLY`.
- Không có canary/feature flag riêng; dùng `auto-correction-frozen` và giảm
  `scheduler.*.batch-size` làm "phanh".

### 7.3. Rotation

- **Webhook secret:** tạo secret mới trên Stripe (Stripe cho phép hai secret song
  song 24h) → thêm vào `payment.stripe.webhook-secrets` (restart/recreate) → xác nhận
  ingress `auth_failed` không tăng → xóa secret cũ trên Stripe → bỏ khỏi config.
  Không bao giờ chỉ có một secret trong lúc rotation.
- **Stripe API key:** restricted key mới → nạp Vault → rolling restart → thu hồi key
  cũ. Recovery/idempotency không phụ thuộc key nên an toàn giữa chừng.
- **KMS key (thiết kế tương lai, chưa chạy được trên runtime hiện tại):** key mới thành `kms-key-id`, key cũ vào `previous-kms-key-ids`; row
  `PENDING` cũ vẫn giải mã được; sau `session.ttl` không còn row nào dùng key cũ →
  bỏ khỏi danh sách.
- **Stripe API version:** đổi qua ADR; test webhook payload mới ở sandbox trước;
  Stripe cho pin version theo endpoint webhook.

### 7.4. Rollback

- Rollback code không được kèm rollback migration nếu migration đã có dữ liệu
  `provider_operations`/`payment_review_cases`/`REVERSED`; down migration chỉ cho
  môi trường chưa có traffic.
- Event v2 là contract: rollback không được phát lại v1 hay đổi schema; consumer
  bỏ qua field lạ.
- Nếu phải tắt Payment: tắt Order gọi Payment trước, để processor chạy hết backlog
  Kafka, rồi mới tắt processor. Webhook endpoint tắt **cuối cùng** (Stripe sẽ retry
  trong lúc tắt ngắn).

## 8. Workers và tác vụ định kỳ

| Worker | Trigger | Input → Output | Lock/idempotency | Chi tiết |
|---|---|---|---|---|
| Outbox CDC Connector (Debezium) | WAL | `outbox_events` → `t.payment.events.v2` | Kafka Connect retry/DLQ | [ADR-005](./decisions/ADR-005-transactional-outbox-cdc.md) |
| Webhook Processor | verified internal topic | Hash check + inbox claim → transition/journal/outbox | DB lease/fencing + aggregate checkpoint | `WF-PAYMENT-002`, `013` |
| Expiration Scanner | 60s | `PENDING/PROCESSING` quá hạn → cancel call → actual result | `SKIP LOCKED`, operation lease | `WF-PAYMENT-005` |
| Provider-operation Recovery | 30s | `REQUESTED`/`UNKNOWN` → same-key before horizon, lookup-only after | DB lease/fencing, backoff/deadline | `WF-PAYMENT-008` |
| Refund Status Poller | 10 phút ± jitter | `PROCESSING` > 1 phút → `Refund.retrieve` → `R2`/`R3` | Lock theo payment; precedence | `WF-PAYMENT-007` |
| Reconciliation | hourly | Object-state + balance-ledger lanes | Session ownership/fencing, window overlap và cursor null/window; freeze | `WF-PAYMENT-006`, `014` |
| Webhook DB Retry | 1 phút | `RECEIVED/RETRY_WAIT` + expired `PROCESSING` lease → claim/process | DB lease/fencing; max attempts → `DEAD` | `WF-PAYMENT-009` |
| Manual Inbox Replay | Manual | Verified retained row → `RETRY_WAIT` | Maker-checker, actor/cause, rate limit | `WF-PAYMENT-009` |
| Dispute deadline alert | Alert rule ngoài module, chưa có `@Scheduled` worker | Query active disputes gần `evidence_due_at`; case `DISPUTE_OPENED` đã được tạo ở D1 | Dashboard/alert ownership; không được mô tả như runtime worker Gate 3 | `WF-PAYMENT-013` |
| Retention Trimmer | 03:30 hàng ngày | Xóa theo `data.md §7`; outbox chỉ dưới CDC watermark | Singleton; batch 5.000; `statement_timeout` 60s | `data.md §7` |

Mọi worker: export `payment_scheduler_run_duration_seconds`, log một dòng
summary/run (`worker`, `scanned`, `acted`, `errors`), và dừng sạch khi nhận SIGTERM
(hoàn tất row đang xử lý, không nhận batch mới).

## 9. Runbook

Quy tắc chung: (1) không sửa `status` bằng SQL — mọi thay đổi qua correction
transition có `actor`/`cause_ref` (`WF-PAYMENT-010`); (2) Stripe Dashboard là nguồn
thật để đối chiếu; (3) ghi timeline vào incident trước khi thao tác.

### 9.1. Initiate lỗi / `503 PROVIDER_OUTCOME_UNKNOWN` tăng

1. Xem `payment_provider_request_duration_seconds{operation_type="CREATE"}` và
   Stripe status page → nếu Stripe lỗi, đi §9.5.
2. Nếu DB: pool timeout? → §9.12.
3. Kiểm tra `provider_operations` `UNKNOWN` có tăng → recovery đang chạy chưa
   (`payment_provider_operations_oldest_age_seconds`).
4. Order phía kia đang retry cùng key — **không** yêu cầu Order đổi key/attempt.

### 9.2. Webhook lag / endpoint lỗi / optimistic conflict cao

1. Endpoint `kafka_error` → Kafka producer: broker, ACL, topic tồn tại. Stripe đang
   retry; sau khi sửa, kiểm tra Stripe Dashboard → Webhooks → failed deliveries; nếu
   quá 3 ngày, "Resend" thủ công theo event.
2. Lag processor → consumer lag + inbox `RECEIVED/RETRY_WAIT/PROCESSING` lease age;
   scale processor/DB retry worker, kiểm tra fencing rejection.
3. Conflict cao → thường do poller/scanner giẫm webhook: tạm tăng `refund-poller.min-age`
   hoặc giảm batch scanner; không tắt precedence.
4. Không bao giờ purge `t.payment.webhooks.raw`.

### 9.3. Outbox không publish

1. Trạng thái connector (`/connectors/<name>/status`), task `FAILED` → log
   Connect; restart task.
2. Replication slot lag (`pg_replication_slots`) → nếu slot bị drop, tạo lại connector
   với snapshot `never` **và** replay outbox từ `created_at` của record cuối trên Kafka
   (đối chiếu `event_id`); consumer dedup theo CloudEvents `id`.
3. Trimmer không được chạy trong lúc này (kiểm tra watermark guard).

### 9.4. Provider operation kẹt

1. `SELECT id, operation_type, status, attempt_count, last_attempted_at FROM
   provider_operations WHERE status IN ('REQUESTED','UNKNOWN') ORDER BY created_at`.
2. Recovery worker có chạy? (`payment_scheduler_run_duration_seconds{worker="recovery"}`).
3. Kiểm tra `safe_replay_until`: đã qua horizon thì **không** gọi create/refund lại.
   Với op `MANUAL_REVIEW_REQUIRED`/review case `PROVIDER_IDENTITY_UNBOUND`: tìm trên
   Stripe Dashboard theo metadata `payment_transaction_id`:
   - đúng một object → correction `MANUAL` bind (`provider_object_id`, transition
     `T0.1`/`T5`/`R1` tương ứng), đóng case `CORRECTION_APPLIED`;
   - không có → đóng `NO_ACTION_NEEDED`; Order mở attempt mới sau khi phiên hết hạn;
   - nhiều object → không tự chọn/cancel; freeze case, ghi evidence, escalate Accounting
     theo maker-checker vì mọi thao tác đều có tác động tiền.

### 9.5. Stripe lỗi / rate limit

1. Xác nhận status.stripe.com; ghi incident.
2. Không thay đổi key/config; hệ thống tự recover qua ProviderOperation.
3. 429: giảm tạm `expiration.batch-size`, tăng `recovery.interval`; không tắt scanner
   (intent sống quá hạn = rủi ro capture muộn, nhưng `INV-PAY-010` vẫn giữ vì chưa
   `EXPIRED`).
4. Sau sự cố: kiểm tra `payment_provider_operations_pending` về 0 và reconciliation
   run kế không drift.

### 9.6. Webhook authentication fail hàng loạt

1. Spike ngay sau deploy/rotation → so `payment.stripe.webhook-secrets` với Stripe
   Dashboard → Webhooks → Signing secret; sửa config và restart/recreate runtime.
2. Payload auth fail không được lưu nên không có auto-reverify. Sau khi sửa secret,
   yêu cầu Stripe Resend delivery (signature/timestamp mới) cho khoảng bị ảnh hưởng;
   chạy object/balance reconciliation để kiểm tra gap.
3. Lẻ tẻ, không trùng deploy → có thể spoof; kiểm tra IP nguồn với danh sách IP
   Stripe; không hành động thêm ngoài block ở gateway.

### 9.7. Review case

Nhận case theo `reason_code`, chuyển `IN_PROGRESS`, `assigned_to`.

| `reason_code` | Việc cần làm | Đóng bằng |
|---|---|---|
| `REFUND_FAILED_AFTER_ACCEPT`, `REFUND_REVERSED` | Xác nhận trên Stripe refund `failed`/`canceled` + `failure_reason`; Stripe chỉ hoàn về phương thức gốc nên liên hệ khách lấy phương thức nhận tiền khác; chuyển khoản/voucher ngoài hệ thống; báo Order (case đã ở manual-review). Nếu `failure_reason = charge_for_pending_refund_disputed` hoặc evidence có `refund_overlap`, xử lý cùng case dispute: chấp nhận hoặc phản hồi dispute thay vì hoàn thủ công để tránh khách nhận tiền hai lần | `MANUAL_REFUND_OUTSIDE_SYSTEM` kèm chứng từ trong `resolution_note` |
| `DISPUTE_OPENED` với `evidence.refund_overlap = true` | Đối chiếu refund `PROCESSING`/`SUCCESS` với dispute trên Stripe; quyết định accept/challenge dispute, không hoàn thêm | `ESCALATED_TO_ACCOUNTING` / `NO_ACTION_NEEDED` |
| `CAPTURE_AFTER_CANCEL` | Order đã nhả hàng; phối hợp Order/CSKH: giao lại (Order lane hậu thanh toán) hoặc refund qua lane refund chính thức của Order (Payment không tự refund) | `ESCALATED_TO_ORDER` |
| `AMOUNT_MISMATCH` | So amount DB ↔ Stripe; nếu Stripe đúng và DB sai → lỗi hệ thống, P1, không auto-correct; Accounting quyết định | `ESCALATED_TO_ACCOUNTING` hoặc `CORRECTION_APPLIED` sau RCA |
| `PROVIDER_IDENTITY_UNBOUND` | §9.4 | theo §9.4 |
| `PROVIDER_RETRIEVE_REJECTED` | Đối chiếu quyền/API version và provider object ID trong evidence; chỉ replay sau khi lỗi 4xx đã được sửa | `CORRECTION_APPLIED` / `NO_ACTION_NEEDED` |
| `DISPUTE_FUNDS_TIE_UNRESOLVED` | Không suy luận từ dispute status; đối chiếu balance transaction thật trong lane `BALANCE_LEDGER`, post journal còn thiếu theo source ID duy nhất | `CORRECTION_APPLIED` / `ESCALATED_TO_ACCOUNTING` |
| `PROVIDER_CREATE_REJECTED` | Đối chiếu `provider_request_id`, lỗi đã sanitize và fingerprint với Stripe; sửa nguyên nhân cấu hình/request. Không retry mù, không tạo operation mới và không sửa status trực tiếp bằng SQL; mọi bind/correction phải đi qua workflow manual correction đã duyệt và FSM. | `CORRECTION_APPLIED` khi đã bind/correct qua FSM; `NO_ACTION_NEEDED` khi xác nhận chắc chắn provider không tạo intent và không cần đổi financial state |
| `WEBHOOK_RETRY_EXHAUSTED` | Xem `processing_error`; sửa nguyên nhân; replay durable `webhook_logs` row đó | `CORRECTION_APPLIED` / `NO_ACTION_NEEDED` |
| `RECONCILIATION_DRIFT` | So `evidence.db_status` ↔ `provider_status`; nếu đích hợp lệ theo FSM → correction `MANUAL`; nếu không → Accounting | `CORRECTION_APPLIED` / `ESCALATED_TO_ACCOUNTING` |
| `DISPUTE_OPENED` | Nộp evidence trước `evidence_due_at`; theo dõi 5 dispute facts và funds journal; Order đã được thông báo qua event | `ESCALATED_TO_ACCOUNTING` hoặc `NO_ACTION_NEEDED` sau terminal |

Không đóng case nếu `requires_manual_review` vẫn `TRUE` sau commit (bug đồng bộ).

### 9.8. KMS/Vault không giải mã

1. `payment_client_secret_decrypt_total{outcome="error"}` theo `client_secret_key_id`
   (log) → key nào hỏng.
2. Runtime local AES chưa bind previous keys: khôi phục đúng local key/key-id đã mã hóa row; không đổi key khi còn ciphertext cần giải mã. KMS rotation theo previous-kms-key-ids là tooling tương lai.
3. Không thể khôi phục → buyer của các attempt `PENDING` đó không tiếp tục được;
   phiên tự hết hạn (≤ 15 phút) → Order mở attempt mới. Không cần thao tác dữ liệu.
4. Kiểm tra initiate mới vẫn mã hóa được; nếu không → P1, tắt Order gọi initiate.

### 9.9. Refund stuck `PROCESSING`

1. `Refund.retrieve(re_xxx)` trên Dashboard: `pending` thật (issuing bank) → chờ,
   ghi chú; `pending` với `pending_reason=insufficient_funds` → số dư Stripe khả dụng
   không đủ, nạp tiền/chờ payout về rồi refund tự tiếp tục (refund quá thời hạn chờ sẽ
   `failed` với `insufficient_funds`); `succeeded`/`failed`/`canceled` → poller đã bỏ
   sót → chạy poller thủ công cho `refund_transaction_id` (lệnh admin, `MANUAL`).
   `requires_action` → mở/kiểm tra review case `RECONCILIATION_DRIFT`; với thẻ không nên
   xảy ra, kiểm tra `payment.stripe.payment-method-types`.
2. `re_xxx` NULL nhưng refund `PROCESSING` → bug; kiểm tra `provider_operations REFUND`.
3. Quá 10 ngày và Stripe vẫn `pending` → mở review case `RECONCILIATION_DRIFT` thủ
   công để CSKH liên hệ khách.

### 9.10. Reconciliation drift / frozen

1. Drift/ledger imbalance tăng → bật `auto-correction-frozen=true`, fail closed
   financial writes nếu DB balance invariant hỏng, mở incident.
2. Đọc `reconciliation_runs` gần nhất + review case `RECONCILIATION_DRIFT`: cùng
   `transition_id`/`event_type`? → nghi bug processor sau deploy → rollback code.
3. Sau RCA: xử lý case tồn (§9.7), chạy reconciliation thủ công cho cửa sổ bị ảnh
   hưởng, unfreeze, theo dõi run kế.
4. Frozen > 24h mà không có incident mở → hỏi chủ sở hữu, unfreeze.
5. Thiết kế sửa ledger bằng reversal journal + journal mới; API/tooling này chưa triển khai. Escalate Accounting, giữ freeze; không coi SQL trực tiếp là remediation đã kiểm chứng và cấm UPDATE/DELETE line cũ.

### 9.11. Decline rate bất thường

1. Phân bố `last_error_code` trong 30 phút: một code trội (`generic_decline` hàng
   loạt) → nghi Stripe/issuer; nhiều `fraudulent`/Radar `blocked` → tấn công card
   testing → phối hợp gateway rate-limit initiate theo `user_id`/IP, bật Radar rule.
2. `DECLINE_LIMIT_EXCEEDED` tăng → ngưỡng 5 có thể thấp với tệp khách; xem xét
   nâng qua config, không đổi ADR.
3. Lỗi tích hợp frontend (Elements gửi sai) thường thấy `incorrect_number`/
   `incorrect_cvc` đồng loạt sau deploy frontend → báo Frontend.

### 9.12. Scheduler chậm / DB

1. `payment_scheduler_batch_size` chạm trần liên tục → tăng instance (scanner,
   recovery) hoặc batch; kiểm tra partial index còn được dùng (`EXPLAIN`).
2. Bloat `payment_status_history`/`webhook_logs` → `pg_stat_user_tables.n_dead_tup`;
   tune `autovacuum_vacuum_scale_factor` cho hai bảng này (0.01).
3. Trimmer: kiểm tra `payment_trimmer_deleted_rows_total`; nếu outbox không được xóa
   do watermark → xem §9.3.
4. Pool exhaustion → tìm transaction dài (`pg_stat_activity`), thường là worker giữ
   TX trong lúc gọi Stripe — **vi phạm thiết kế** (`data.md §5`), sửa code.

### 9.13. Thao tác vận hành có kiểm soát

| Thao tác | Quyền | Audit |
|---|---|---|
| Bật/tắt `auto-correction-frozen` | Payment on-call | Log + `reconciliation_runs.auto_correction_frozen` |
| Manual webhook-log replay | Payment on-call | `payment_status_history.triggered_by = MANUAL`, `actor`, `cause_ref` |
| Correction transition thủ công | Payment on-call + Accounting duyệt với `AMOUNT_MISMATCH` | history `MANUAL` + review case `CORRECTION_APPLIED` |
| Đóng review case | CSKH (refund), Accounting (amount/dispute), Payment (kỹ thuật) | `resolved_by`, `resolution_type`, `resolution_note` |
| Đọc webhook ciphertext đã giải mã | Payment on-call break-glass + approver | Access log; không export ra ticket; chỉ trong 90 ngày |
| Post correction/reversal journal | Accounting Ops tạo + người khác duyệt | Actor/approver/cause + immutable journal links |
| Nộp dispute evidence/đóng case | Finance Ops, maker-checker | Actor/approver + provider response + deadline |
| Rotation secret/key | Platform + Payment | Change ticket |

Không có thao tác nào ghi thẳng `status` bằng SQL, kể cả incident. Vận hành phải
claim case rồi gọi correction endpoint bằng một FSM trigger; endpoint bắt buộc
maker-checker, amount/currency khớp aggregate và ghi history `MANUAL`, actor,
cause_ref, outbox/journal trong transaction aggregate.

## 10. Backup, restore và disaster recovery

- PostgreSQL bật continuous WAL archiving/PITR; backup mã hóa và restore test hàng
  tháng. Mục tiêu RPO = 0 cho transaction đã commit trong failure domain được thiết
  kế, RTO ≤ 60 phút.
- Sau restore, giữ command/webhook financial writes ở fail-closed; kiểm migration,
  deferred ledger balance, max aggregate checkpoint, replication slot/outbox LSN và
  đối soát object + balance từ restore point tới hiện tại.
- Chỉ mở writes sau khi không có journal mất cân bằng, không duplicate provider
  movement, cursor không vượt dữ liệu đã restore và các `UNKNOWN` operation đã vào
  recovery an toàn. Ghi kết quả drill/RCA với owner và thời gian thực tế.
