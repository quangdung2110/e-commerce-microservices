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

> Acceptance criteria vận hành cho Payment Service: SLO, cấu hình, metric, alert,
> failure mode, triển khai, worker và runbook. Chưa có runtime, dashboard hay alert
> rule thực tế để xác minh; mọi con số là **mục tiêu thiết kế** và phải được đo lại
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
| Review case time-to-resolve | p90 `resolved_at − opened_at` | p90 ≤ 2 ngày làm việc (Cao: 4 giờ) | Tiền của khách đang treo |

Error budget theo tháng; khi cạn budget của "Financial fact correctness" thì đóng
băng thay đổi FSM/webhook cho tới khi có RCA.

### 1.2. Health

| Probe | Kiểm tra | Ghi chú |
|---|---|---|
| Liveness | Process + event loop | Không gọi dependency |
| Readiness — API command (`initiate`/`cancel`/`refund`) | PostgreSQL `SELECT 1` trong pool; KMS/Vault key `client_secret` load được; Stripe secret key đã nạp (không gọi Stripe) | Redis **không** chặn readiness (fallback DB) |
| Readiness — Webhook endpoint | Kafka producer connected và metadata `t.payment.webhooks.raw` có | Không phụ thuộc PostgreSQL: DB down vẫn phải nhận webhook và dump Kafka |
| Readiness — Webhook Processor / scheduler | PostgreSQL + Kafka consumer group assigned + Stripe key | Báo riêng, không kéo API instance ra khỏi LB |
| Startup | Migration đã ở version kỳ vọng; config bắt buộc có mặt; `whsec_` decode được | Fail-fast nếu thiếu `sk_`/`whsec_`/KMS key |
| Dependency check nội bộ (`/internal/health/deps`) | Trạng thái từng dependency + `auto_correction_frozen` + version connector Debezium | Chỉ cho vận hành, không dùng cho LB |

## 2. Cấu hình cần công bố

Property naming đề xuất `payment.<nhóm>.<tên>`; giá trị secret chỉ qua Vault/Secret
Manager, **không** commit và không log. Đổi property có đánh dấu "restart" cần
rolling restart; các property khác reload được.

### 2.1. Stripe

| Property | Mục đích | Giá trị thiết kế | Secret | Restart |
|---|---|---|---|---|
| `payment.stripe.secret-key` | `StripeClient` singleton | `sk_test_…` (dev/staging), `sk_live_…` (prod) | Có | Có |
| `payment.stripe.publishable-key` | Trả cho Order qua initiate/`client-data` | `pk_…` cùng sandbox với secret key | Không | Không |
| `payment.stripe.webhook-secrets` | Verify `Stripe-Signature`; **danh sách** (tối đa 2) để rotation không gián đoạn | `whsec_…` | Có | Không (reload) |
| `payment.stripe.api-version` | Pin API version cho SDK và webhook payload | Một version cố định, đổi qua ADR | Không | Có |
| `payment.stripe.timeout.connect/read` | HTTP timeout SDK | 5s / 15s | Không | Không |
| `payment.stripe.max-network-retries` | Retry của SDK cho lỗi mạng (idempotent nhờ key) | 2 | Không | Không |
| `payment.stripe.webhook-window-seconds` | Cửa sổ `\|now − t\|` | 300 | Không | Không |
| `payment.stripe.decline.non-retryable-codes` | Policy `RULE-PAY-014` | `fraudulent,lost_card,stolen_card,pickup_card,restricted_card,security_violation,merchant_blacklist` + Radar `blocked` | Không | Không |
| `payment.stripe.decline.max-per-attempt` | `max_declines_per_attempt` | 5 | Không | Không |

Webhook endpoint đăng ký trên Stripe Dashboard/CLI phải subscribe đúng tập event:
`payment_intent.processing`, `payment_intent.succeeded`,
`payment_intent.payment_failed`, `payment_intent.canceled`, `charge.refunded`,
`charge.refund.updated`, `refund.failed`, `charge.dispute.created`. Event khác bị
`IGNORED` — subscribe thừa chỉ tốn tài nguyên, thiếu thì mất fact.

### 2.2. Phiên, khóa, mã hóa

| Property | Mục đích | Giá trị thiết kế | Secret | Restart |
|---|---|---|---|---|
| `payment.session.ttl` | `expires_at = created_at + ttl` | 15 phút | Không | Không |
| `payment.session.client-secret-margin` | `client_expires_at = expires_at − margin` | 30s | Không | Không |
| `payment.crypto.kms-key-id` | Envelope key cho `client_secret_ciphertext`; ghi vào `client_secret_key_id` | Theo môi trường | Có | Có |
| `payment.crypto.previous-kms-key-ids` | Giải mã row cũ trong cửa sổ rotation | Danh sách | Có | Không |
| `payment.crypto.local-key` | **Chỉ profile `dev`/`test`**: AES-256 key cố định thay KMS; startup fail nếu set ở `prod` | base64 32 byte | Có (local) | Có |
| `payment.redis.idempotency-ttl.initiate` | Fast-path replay initiate | 15 phút + 60s | Không | Không |
| `payment.redis.idempotency-ttl.command` | Cancel/refund | 7 ngày | Không | Không |
| `payment.redis.lock-ttl` | `payment:lock:*` | 30s (≥ Stripe read timeout ×2) | Không | Không |

### 2.3. Worker và retention

| Property | Mục đích | Giá trị thiết kế |
|---|---|---|
| `payment.scheduler.expiration.interval` / `batch-size` | Expiration Scanner | 60s / 200 |
| `payment.scheduler.recovery.interval` / `backoff` / `max-attempts` | Provider-operation Recovery | 30s / 30s→5 phút (×2, jitter) / 20 |
| `payment.scheduler.refund-poller.interval` / `min-age` | Refund Status Poller | 10 phút (jitter ±2) / 1 phút |
| `payment.scheduler.reconciliation.cron` / `window-lag` | Reconciliation | hourly / 5 phút |
| `payment.reconciliation.auto-correction-frozen` | Cờ vận hành, ghi vào `reconciliation_runs` | `false` |
| `payment.webhook.retry.max` / `backoff` | Auto-Retry Worker | 8 / 1 phút→1 giờ (×2) |
| `payment.webhook.consumer-group` | Webhook Processor | `payment-webhook-processor` |
| `payment.retention.outbox-days` / `webhook-log-months` / `reconciliation-run-months` / `trimmer.batch-size` / `trimmer.cron` | Retention Trimmer (`data.md §7`) | 15 / 12 / 12 / 5.000 / 03:30 hàng ngày |

### 2.4. Hạ tầng

PostgreSQL DSN/pool (đề xuất pool 20/instance, `statement_timeout` 5s cho API, 60s
cho worker), Redis endpoint/auth, Kafka bootstrap/security và hai topic:

- `t.payment.events.v2`: 6 partition, replication 3, retention tối thiểu 15 ngày,
  max message 64 KiB, classification `INTERNAL`; chỉ Debezium được produce.
- `t.payment.webhooks.raw`: 6 partition, replication 3, retention 7 ngày, max
  message 256 KiB, classification `CONFIDENTIAL`; ACL chỉ cho Payment API/worker.

Debezium connector dùng `table.include.list=public.outbox_events`, outbox SMT route
theo `aggregate_type`, key `partition_key` và chuyển `payload` full structured
CloudEvent nguyên vẹn thành Kafka value. Cột `headers` chỉ mirror metadata cho
observability, không phải wire authority ([ADR-015](./decisions/ADR-015-canonical-asyncapi-and-structured-cloudevents.md)).
Credential là secret.

## 3. Quan sát hệ thống

### 3.1. Metric

Quy ước: Micrometer/Prometheus, prefix `payment_`; **không** label bằng
`order_id`/`payment_transaction_id`/`user_id` (cardinality + PII); label cho phép:
`route`, `status`, `event_type`, `transition_id`, `reason_code`, `operation_type`,
`outcome`, `worker`.

| Metric | Kiểu | Label | Ý nghĩa / SLI liên quan |
|---|---|---|---|
| `payment_http_requests_total`, `payment_http_request_duration_seconds` | counter, histogram | `route`, `status` | Initiate availability/latency |
| `payment_webhook_received_total` | counter | `outcome` (`accepted`, `kafka_error`) | Endpoint availability |
| `payment_webhook_processing_duration_seconds` | histogram | `event_type`, `outcome` (`PROCESSED`, `IGNORED`, `FAILED_*`, `DUPLICATE`) | Webhook-to-fact |
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
| `payment_refund_processing_age_seconds` | gauge | — | Tuổi refund `PROCESSING` cũ nhất |
| `payment_refund_outcomes_total` | counter | `outcome` (`SUCCESS`, `FAILED`, `REVERSED`) | Reversal rate |
| `payment_review_cases_open` | gauge | `reason_code` | Backlog thủ công |
| `payment_review_case_age_seconds` | gauge | `reason_code` | Case mở lâu nhất |
| `payment_reconciliation_runs_total`, `payment_reconciliation_drift_total`, `payment_reconciliation_corrections_total` | counter | `outcome` / `reason` | Drift rate = drift / scanned |
| `payment_reconciliation_frozen` | gauge (0/1) | — | Cờ freeze đang bật |
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
3. **Recovery & review:** provider ops pending/oldest age, review cases open theo
   `reason_code`, refund processing age, reconciliation drift/corrections/frozen.
4. **Dependencies:** Stripe latency/error theo `operation_type`, DB pool, Redis
   errors, KMS decrypt errors.

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
| `FAILED_SIGNATURE` hàng loạt | > 10 trong 5 phút | P1 (sai key) / P2 (spoof lẻ tẻ) | §9.6 |
| KMS decrypt lỗi | `payment_client_secret_decrypt_total{outcome="error"}` > 0 | P1 | §9.8 |
| Refund stuck | `payment_refund_processing_age_seconds` > 7 ngày | P2 | §9.9 |
| Reversal rate | `REVERSED` / `SUCCESS` refund > 1% / 7 ngày | P2 | §9.7 |
| Review case backlog | `payment_review_cases_open` > 20, hoặc age > 4h với `reason_code` ∈ {`CAPTURE_AFTER_CANCEL`, `REFUND_REVERSED`} | P2 | §9.7 |
| Reconciliation drift | drift/scanned > 0.5% một run, hoặc 2 run liên tiếp `FAILED` | P2 | §9.10 |
| Reconciliation frozen quá lâu | `payment_reconciliation_frozen == 1` > 24h | P3 | §9.10 |
| Decline rate bất thường | `T3a`/initiate > baseline ×2 trong 30 phút | P2 (fraud/tích hợp Elements) | §9.11 |
| Optimistic conflict | conflicts/transitions > 5% | P3 | §9.2 |
| Scheduler chậm | `payment_scheduler_run_duration_seconds` p95 > interval/2 | P3 | §9.12 |
| Trimmer không chạy | `payment_trimmer_deleted_rows_total` không tăng 48h hoặc `payment_outbox_pending_age_seconds` tăng đều | P3 | §9.12 |

## 5. Failure mode và hành vi suy giảm

| Sự cố | Phát hiện | Tác động | Hành vi hệ thống | Khôi phục |
|---|---|---|---|---|
| PostgreSQL unavailable | API `503`, readiness command fail | Không initiate/cancel/refund; buyer đang ở Elements vẫn thanh toán được trên Stripe | Webhook endpoint vẫn `200` + dump Kafka (readiness riêng); processor/scheduler dừng, retry backoff; Stripe fact chờ trong Kafka | DB lên → processor tiêu thụ backlog theo thứ tự; không mất fact |
| PostgreSQL chậm/lock | Latency, pool timeout | Initiate p95 vượt SLO | `statement_timeout` cắt; scanner giảm batch | Tuning; xem §9.12 |
| Redis unavailable | Redis error rate | Không có | Idempotency/lock fallback DB (`payment_idempotency`, unique constraint); worker không lấy được lock → skip lượt này | Tự hồi; không cần thao tác |
| Kafka unavailable | Endpoint `kafka_error` | Stripe nhận `5xx` → **Stripe retry** 3–5 lần trong ~3 ngày (không mất) | Outbox tích lũy trong DB; CDC relay sau | Kafka lên → Debezium bắt kịp; nếu Stripe đã hết retry, replay từ Stripe Dashboard ("Resend") hoặc reconciliation |
| Debezium/Kafka Connect down | Outbox age | Order không nhận fact (saga chờ) | State DB đúng; outbox không bị trimmer xóa vì dưới watermark | Restart connector; **không** publish tay |
| Stripe API lỗi/timeout | Provider error rate | Initiate trả `503` retryable; cancel `CANCEL_PENDING`; refund `503` | ProviderOperation `UNKNOWN`; recovery replay cùng key; không bao giờ suy luận non-capture | Stripe hồi → recovery bind trong ≤ 10 phút |
| Stripe rate limit (429) | Provider `error` với code 429 | Như trên | SDK retry có backoff; scheduler giảm batch | Giảm interval scanner/poller tạm thời |
| Webhook miss (Stripe outage/network) | Refund age, reconciliation drift | Fact đến trễ | Poller (refund) và reconciliation (payment) chốt từ `retrieve` | Không cần thao tác |
| Sai/đổi `whsec_` | `FAILED_SIGNATURE` spike | Fact không được áp | Row giữ `raw_body`; Auto-Retry re-verify với danh sách secret hiện hành | Cập nhật `payment.stripe.webhook-secrets` (reload) → retry tự pass |
| KMS/Vault không giải mã được | decrypt `error` | Buyer reload trang không lấy được secret; initiate mới vẫn mã hóa được (key hiện hành) hoặc fail nếu key hiện hành hỏng | `client-data` trả `usable=false`; phiên hết hạn → Order mở attempt mới | Sửa key; thêm key cũ vào `previous-kms-key-ids` |
| Refund fail/reverse sau accept | Review case | Khách chưa nhận tiền | Không tạo refund thứ hai; case `OPEN` | Hoàn tay, đóng case |
| Capture sau cancel (race) | Review case `CAPTURE_AFTER_CANCEL` | Order đã nhả hàng nhưng tiền đã thu | Payment `SUCCESS`; Order lane hậu thanh toán/financial reconciliation | Order/CSKH quyết định giao lại hoặc refund qua lane refund |
| Dispute/chargeback | `charge.dispute.created` → review case | Stripe giữ tiền + phí | Chưa có FSM (`data.md §7.1`) | Nộp evidence trên Stripe Dashboard trong hạn |
| Drift tăng đột biến | Reconciliation | Có thể do bug transition | Bật `auto-correction-frozen`; chỉ mở case | RCA rồi unfreeze |

Suy giảm chấp nhận được: fact đến Order trễ; refund accepted nhưng final trễ;
`client-data` tạm không dùng được. **Không** chấp nhận: transition không qua guard,
publish trước commit, refund thứ hai, terminal non-capture khi intent còn sống, mất
webhook đã nhận `200`.

## 6. Scaling và capacity

Giả định ban đầu (cần load test xác nhận): 50 initiate/s peak, 1 payment ≈ 4–6
webhook, 1 order ≈ 1.3 attempt, refund ≈ 2% order.

| Thành phần | Scale | Giới hạn |
|---|---|---|
| API server | Ngang, stateless; 2+ instance | Pool DB 20/instance; Stripe rate limit (~100 req/s live mặc định) |
| Webhook endpoint | Cùng process API hoặc tách; rất nhẹ | Kafka producer throughput |
| Webhook Processor | Theo partition `t.payment.webhooks.raw` (6) → tối đa 6 consumer | Cùng `pi_xxx` không đảm bảo cùng partition (key là candidate event ID hoặc hash raw body); optimistic lock + precedence xử lý |
| Expiration Scanner | Nhiều instance, `SKIP LOCKED`, batch 200 | Mỗi row = 1 Stripe cancel → ≤ 200 call/phút/instance |
| Recovery / Poller | Nhiều instance, lock theo payment | Stripe rate limit |
| Reconciliation, Trimmer | Singleton (`payment:lock:reconciliation`) | Cửa sổ 1 giờ; ~3.600 retrieve/giờ ở peak |
| PostgreSQL | Một primary + replica cho read `GET`/dashboard | Bảng tăng đơn điệu ([ADR-013](./decisions/ADR-013-single-oltp-retention-no-partition.md)); partial index giữ tập hoạt động nhỏ; theo dõi bloat, `autovacuum` cho `payment_status_history`/`webhook_logs` |

Ước lượng dung lượng: `payment_transactions` ~1 KB/row, `webhook_logs` ~3–8 KB/row
(raw body) → 1 triệu payment/tháng ≈ 1 GB payments + 6–8 GB webhook/tháng; retention
webhook 12 tháng là driver dung lượng chính.

## 7. Triển khai và rollback

### 7.1. Thứ tự lần đầu

1. PostgreSQL: migration tạo 9 bảng (`development.md §8`), user app chỉ có DML +
   `SELECT` trên `outbox_events` cho Debezium user (REPLICATION).
2. Vault/KMS: nạp `sk_`, `whsec_` (rỗng cho tới bước 7), KMS key id.
3. Redis, Kafka topic (`t.payment.webhooks.raw` 6 partition retention 7 ngày;
   `t.payment.events.v2` theo platform), consumer group.
4. Debezium connector với outbox SMT; xác nhận `aggregate_type=Payment` route đúng
   topic và key = `partition_key`.
5. Deploy Payment (API + processor + scheduler) với `auto-correction-frozen=true`
   cho tuần đầu.
6. Smoke: initiate test mode → `PENDING`; `client-data` → `usable=true`.
7. Đăng ký webhook endpoint trên Stripe (đúng tập event §2.1), lấy `whsec_` → nạp
   Vault → reload; `stripe trigger payment_intent.succeeded` → thấy `PROCESSED`.
8. Bật Order gọi Payment; theo dõi dashboard 1–3 ngày rồi unfreeze.

### 7.2. Rolling deploy

- API và processor deploy rolling; worker mới và cũ chạy song song an toàn nhờ
  optimistic lock + unique constraint + `SKIP LOCKED`.
- Migration **expand/contract**: thêm cột/index nullable trước, code sau, xóa cột ở
  release kế. Index trên bảng lớn tạo `CONCURRENTLY`.
- Không có canary/feature flag riêng; dùng `auto-correction-frozen` và giảm
  `scheduler.*.batch-size` làm "phanh".

### 7.3. Rotation

- **Webhook secret:** tạo secret mới trên Stripe (Stripe cho phép hai secret song
  song 24h) → thêm vào `payment.stripe.webhook-secrets` (reload) → xác nhận
  `FAILED_SIGNATURE` không tăng → xóa secret cũ trên Stripe → bỏ khỏi config.
  Không bao giờ chỉ có một secret trong lúc rotation.
- **Stripe API key:** restricted key mới → nạp Vault → rolling restart → thu hồi key
  cũ. Recovery/idempotency không phụ thuộc key nên an toàn giữa chừng.
- **KMS key:** key mới thành `kms-key-id`, key cũ vào `previous-kms-key-ids`; row
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
| Webhook Processor | `t.payment.webhooks.raw` | Raw webhook → verify → transition + outbox | `ON CONFLICT (stripe_event_id)` + `version` + precedence | `WF-PAYMENT-002`, `WF-PAYMENT-012` |
| Expiration Scanner | 60s | `PENDING` quá hạn → `CANCEL` op → `PaymentIntent.cancel` → `EXPIRED` (hoặc `SUCCESS`) | `SKIP LOCKED`, partial index, lock theo payment | `WF-PAYMENT-005` |
| Provider-operation Recovery | 30s | `REQUESTED`/`UNKNOWN` quá `next_retry_at` → replay cùng key → bind / review case | Key bất biến, backoff, `max-attempts` | `WF-PAYMENT-008` |
| Refund Status Poller | 10 phút ± jitter | `PROCESSING` > 1 phút → `Refund.retrieve` → `R2`/`R3` | Lock theo payment; precedence | `WF-PAYMENT-007` |
| Reconciliation | hourly | Cửa sổ → `retrieve` → `reconciliation_runs` + `CORR` / review case | Singleton; `CORR` idempotent; freeze | `WF-PAYMENT-006` |
| Webhook Auto-Retry | 1 phút | `FAILED_SIGNATURE`/`UNPROCESSED`/`FAILED_PROCESSING` quá `next_retry_at` → re-verify → re-push | `retry_count`, dedup `stripe_event_id` | `WF-PAYMENT-009` |
| Dead-letter Replay | Manual | Filter → replay batch | Re-validate idempotency, rate limit, `MANUAL` audit | `WF-PAYMENT-009` |
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
2. Lag processor → consumer lag theo partition; scale processor tới số partition;
   kiểm tra DB lock/`FAILED_PROCESSING` tăng.
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
3. Với op `MANUAL_REVIEW_REQUIRED`/review case `PROVIDER_IDENTITY_UNBOUND`: tìm trên
   Stripe Dashboard theo metadata `payment_transaction_id`:
   - đúng một object → correction `MANUAL` bind (`provider_object_id`, transition
     `T0.1`/`T5`/`R1` tương ứng), đóng case `CORRECTION_APPLIED`;
   - không có → đóng `NO_ACTION_NEEDED`; Order mở attempt mới sau khi phiên hết hạn;
   - nhiều object (không nên xảy ra nhờ idempotency key) → giữ tất cả trừ một, cancel
     phần thừa trên Dashboard, ghi vào `evidence`, escalate Accounting.

### 9.5. Stripe lỗi / rate limit

1. Xác nhận status.stripe.com; ghi incident.
2. Không thay đổi key/config; hệ thống tự recover qua ProviderOperation.
3. 429: giảm tạm `expiration.batch-size`, tăng `recovery.interval`; không tắt scanner
   (intent sống quá hạn = rủi ro capture muộn, nhưng `INV-PAY-010` vẫn giữ vì chưa
   `EXPIRED`).
4. Sau sự cố: kiểm tra `payment_provider_operations_pending` về 0 và reconciliation
   run kế không drift.

### 9.6. `FAILED_SIGNATURE` hàng loạt

1. Spike ngay sau deploy/rotation → so `payment.stripe.webhook-secrets` với Stripe
   Dashboard → Webhooks → Signing secret; sửa config (reload).
2. Auto-Retry re-verify tự động; nếu đã hết `retry.max`, chạy manual webhook-log
   replay theo time range (v1 không có Kafka DLQ cho raw webhook).
3. Lẻ tẻ, không trùng deploy → có thể spoof; kiểm tra IP nguồn với danh sách IP
   Stripe; không hành động thêm ngoài block ở gateway.

### 9.7. Review case

Nhận case theo `reason_code`, chuyển `IN_PROGRESS`, `assigned_to`.

| `reason_code` | Việc cần làm | Đóng bằng |
|---|---|---|
| `REFUND_FAILED_AFTER_ACCEPT`, `REFUND_REVERSED` | Xác nhận trên Stripe refund `failed` + `failure_reason`; liên hệ khách lấy phương thức nhận tiền khác; chuyển khoản/voucher ngoài hệ thống; báo Order (case đã ở manual-review) | `MANUAL_REFUND_OUTSIDE_SYSTEM` kèm chứng từ trong `resolution_note` |
| `CAPTURE_AFTER_CANCEL` | Order đã nhả hàng; phối hợp Order/CSKH: giao lại (Order lane hậu thanh toán) hoặc refund qua lane refund chính thức của Order (Payment không tự refund) | `ESCALATED_TO_ORDER` |
| `AMOUNT_MISMATCH` | So amount DB ↔ Stripe; nếu Stripe đúng và DB sai → lỗi hệ thống, P1, không auto-correct; Accounting quyết định | `ESCALATED_TO_ACCOUNTING` hoặc `CORRECTION_APPLIED` sau RCA |
| `PROVIDER_IDENTITY_UNBOUND` | §9.4 | theo §9.4 |
| `WEBHOOK_RETRY_EXHAUSTED` | Xem `processing_error`; sửa nguyên nhân; replay durable `webhook_logs` row đó | `CORRECTION_APPLIED` / `NO_ACTION_NEEDED` |
| `RECONCILIATION_DRIFT` | So `evidence.db_status` ↔ `provider_status`; nếu đích hợp lệ theo FSM → correction `MANUAL`; nếu không → Accounting | `CORRECTION_APPLIED` / `ESCALATED_TO_ACCOUNTING` |
| `DISPUTE_OPENED` | Nộp evidence trên Stripe trước `evidence_due_by`; theo dõi kết quả; Order được thông báo qua CSKH (chưa có event) | `ESCALATED_TO_ACCOUNTING` |

Không đóng case nếu `requires_manual_review` vẫn `TRUE` sau commit (bug đồng bộ).

### 9.8. KMS/Vault không giải mã

1. `payment_client_secret_decrypt_total{outcome="error"}` theo `client_secret_key_id`
   (log) → key nào hỏng.
2. Key cũ bị xóa sớm → khôi phục vào `previous-kms-key-ids`.
3. Không thể khôi phục → buyer của các attempt `PENDING` đó không tiếp tục được;
   phiên tự hết hạn (≤ 15 phút) → Order mở attempt mới. Không cần thao tác dữ liệu.
4. Kiểm tra initiate mới vẫn mã hóa được; nếu không → P1, tắt Order gọi initiate.

### 9.9. Refund stuck `PROCESSING`

1. `Refund.retrieve(re_xxx)` trên Dashboard: `pending` thật (issuing bank) → chờ,
   ghi chú; `succeeded`/`failed` → poller đã bỏ sót → chạy poller thủ công cho
   `refund_transaction_id` (lệnh admin, `MANUAL`).
2. `re_xxx` NULL nhưng refund `PROCESSING` → bug; kiểm tra `provider_operations REFUND`.
3. Quá 10 ngày và Stripe vẫn `pending` → mở review case `RECONCILIATION_DRIFT` thủ
   công để CSKH liên hệ khách.

### 9.10. Reconciliation drift / frozen

1. Drift tăng đột biến → bật `auto-correction-frozen=true` ngay (reload), mở incident.
2. Đọc `reconciliation_runs` gần nhất + review case `RECONCILIATION_DRIFT`: cùng
   `transition_id`/`event_type`? → nghi bug processor sau deploy → rollback code.
3. Sau RCA: xử lý case tồn (§9.7), chạy reconciliation thủ công cho cửa sổ bị ảnh
   hưởng, unfreeze, theo dõi run kế.
4. Frozen > 24h mà không có incident mở → hỏi chủ sở hữu, unfreeze.

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
| Đọc `webhook_logs.raw_body` | Payment on-call, có lý do incident | Access log; không export ra ticket |
| Rotation secret/key | Platform + Payment | Change ticket |

Không có thao tác nào ghi thẳng `status` bằng SQL; nếu bắt buộc trong incident, phải
kèm insert `payment_status_history` (`MANUAL`, `actor`, `cause_ref = incident id`)
trong cùng transaction và review case `CORRECTION_APPLIED`.
