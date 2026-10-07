# Observability

**Trạng thái:** `proposed`

## 1. Ba câu hỏi hệ thống phải trả lời được

Notification là service "vô hình cho đến khi hỏng". Observability được thiết kế quanh ba câu hỏi, theo thứ tự tần suất thực tế:

| Câu hỏi | Ai hỏi | Công cụ |
|---|---|---|
| **"Email của user X / đơn Y đâu?"** | Support, hằng ngày | Timeline một notification (§5), status API |
| **"Lane critical có đang trễ không?"** | Alert, tự động | `delivery_pending_age` theo lane (§2) |
| **"Provider / dependency nào đang hỏng?"** | Ops khi sự cố | Error rate theo provider, breaker state, bulkhead saturation (§2) |

Tổng số lỗi không phải câu hỏi: lúc provider sự cố nó luôn cao và không nói phải làm gì ([06-backpressure.md](./06-backpressure.md) §7).

## 2. Metric

Prometheus, prefix `notification_`. Label chuẩn: `lane`, `channel`, `template`, `provider`, `stage`, `reason_code`. **Không bao giờ** có `user_id`, `delivery_id`, `subject`, `email_hash` trong label ([11-privacy.md](./11-privacy.md) §3).

### 2.1 Ranh giới 1 — consumer

| Metric | Kiểu | Label | Ý nghĩa |
|---|---|---|---|
| `ingest_events_total` | counter | `lane`, `source`, `decision`, `reason_code` | Mỗi event nhận; `decision` = DECIDED/SKIPPED/FAILED/EXPIRED/IGNORED/DUPLICATE |
| `ingest_duration_seconds` | histogram | `lane` | Từ poll tới commit offset; SLO nội bộ p99 < 50 ms |
| `kafka_consumer_lag` | gauge | `lane`, `topic`, `partition` | Từ Kafka client; lag critical > 100 là alert |
| `decide_step_rejections_total` | counter | `lane`, `step` (1–13), `reason_code` | Bước nào từ chối — cho biết producer sai contract hay chính sách chặn |
| `dead_letter_total` | counter | `topic`, `reason` | Message publish sang topic `*.dlq` (envelope hỏng, DB lỗi quá số lần retry) |
| `subject_sequence_events_total` | counter | `source`, `outcome` (`advanced`/`duplicate`/`superseded`/`collision`/`missing`) | Sức khoẻ monotonic sequence; `collision`/Order `missing` là producer contract bug |

### 2.2 Ranh giới 2 — deliver

| Metric | Kiểu | Label | Ý nghĩa |
|---|---|---|---|
| `delivery_pending_count` | gauge | `lane`, `channel` | Row `PENDING`/`RETRY` có `next_attempt_at <= now` (sẵn sàng nhưng chưa được claim) |
| `delivery_pending_age_seconds` | gauge (p50/p99 tính từ DB) | `lane`, `channel` | `now - created_at` của row sẵn sàng cũ nhất — **metric quan trọng nhất** |
| `delivery_scheduled_count` | gauge | `lane` | Row có `next_attempt_at` trong tương lai (quiet hours, backoff) — tách khỏi pending để không báo động giả |
| `delivery_claim_duration_seconds` | histogram | `lane` | Câu claim; > 20 ms là dấu hiệu index/bloat |
| `delivery_attempts_total` | counter | `lane`, `channel`, `provider`, `outcome` (`sent`/`retryable_error`/`permanent_error`/`breaker_open`/`rate_limited`/`lane_paused`/`daily_quota`/`ordering_busy`/`ordering_lease_lost`) | Mỗi lần thử hoặc release (các outcome release không tính attempt) |
| `delivery_send_duration_seconds` | histogram | `provider`, `lane` | Chỉ thời gian gọi provider |
| `delivery_finished_total` | counter | `lane`, `channel`, `template`, `final_status`, `reason_code` | Trạng thái cuối: DELIVERED/BOUNCED/FAILED/EXPIRED/SKIPPED |
| `delivery_end_to_end_seconds` | histogram | `lane`, `channel` | `sent_at - request.received_at`; SLO critical p99 < 5 s, transactional < 60 s |
| `delivery_possibly_duplicate_total` | counter | `lane` | Sức khoẻ lease/heartbeat ([04-idempotency.md](./04-idempotency.md) C3) |
| `delivery_lease_expired_total` | counter | `lane` | Reaper trả row về RETRY |
| `ordering_lease_contention_total` | counter | `source` | Worker không acquire được per-subject lease; tăng liên tục có thể là hot subject/lease kẹt |
| `ordering_lease_oldest_age_seconds` | gauge | `source` | Tuổi lease Order đã hết hạn nhưng chưa được thu hồi (reaper xuất mỗi `jobs.metrics_interval`; về 0 khi đã thu hồi). Lease còn hạn nhưng owner treo không hiện ở đây — xem `ordering_lease_contention_total` |
| `ordering_lease_lost_total` | counter | `source`, `stage` (`before_send`/`during_send`) | Mất ownership; before_send không gọi provider, during_send là kết quả delivery không chắc chắn |
| `retry_budget_ratio` | gauge | `lane` | Retry / tổng gửi trong cửa sổ 1 phút; > 0,2 kích hoạt budget |
| `lane_paused` | gauge | `lane`, `channel` | 1 khi công tắc ops đang pause |

### 2.3 Dependency và bulkhead

| Metric | Kiểu | Label | Ý nghĩa |
|---|---|---|---|
| `dependency_request_duration_seconds` | histogram | `dependency` (`auth_contact`/`auth_content`/`ses`/`smtp`/`sqs`), `lane`, `status` | |
| `dependency_error_rate` | derived | `dependency` | 5xx + timeout / tổng, 5 phút |
| `circuit_breaker_state` | gauge (0 closed / 1 half-open / 2 open) | `dependency` | |
| `bulkhead_in_use` / `bulkhead_capacity` | gauge | `lane`, `channel`, `resource` (`threads`/`http_conns`/`db_conns`) | Saturation theo vách ngăn |
| `rate_limiter_wait_seconds` | histogram | `lane`, `channel` | Chờ token; tăng nghĩa là quota lane không đủ |
| `rate_limiter_tokens_available` | gauge | `lane`, `channel` | |
| `provider_daily_quota_used_ratio` | gauge | `provider` | Bộ đếm ngày / `Max24HourSend`; 0,8 là ngưỡng dừng bulk |
| `contact_cache_total` | counter | `result` (`hit`/`miss`) | Cache email 15 phút; hit ratio suy ra bằng PromQL |
| `frequency_cap_degraded` | gauge | — | 1 khi Redis lỗi và cap đang fail-open |
| `render_locale_fallback_total` | counter | `template` | Locale thiếu, dùng `vi` |

### 2.4 Track

| Metric | Kiểu | Label | Ý nghĩa |
|---|---|---|---|
| `sqs_messages_received_total` | counter | `event_type` | |
| `sqs_oldest_message_age_seconds` | gauge | — | Từ CloudWatch `ApproximateAgeOfOldestMessage` hoặc tự tính |
| `provider_event_processed_total` | counter | `event_type`, `outcome` (`applied`/`ignored_lower_rank`/`unmatched`/`ignored`/`invalid_signature`/`duplicate`/`malformed`) | |
| `provider_event_unmatched_age_seconds` | gauge | — | UNMATCHED cũ nhất; > 5 phút là bug correlate |
| `bounce_total` / `complaint_total` | counter | `lane`, `bounce_type` / `lane` | Nguồn cho `bounce_rate` / `complaint_rate` |
| `bounce_rate` / `complaint_rate` | derived, cửa sổ 24 h | `lane` | Bounce / sent; complaint / delivered — ngưỡng SES 5 % / 0,1 % |
| `suppression_added_total` | counter | `reason` | |

### 2.5 Dữ liệu và job

| Metric | Kiểu | Label | Ý nghĩa |
|---|---|---|---|
| `partition_future_months` | gauge | `table` | < 1 là alert khẩn (INSERT sắp lỗi) |
| `purge_rows_deleted_total` | counter | `table` | Sổ dedup, outbox |
| `outbox_unpublished_count` / `outbox_oldest_age_seconds` | gauge | — | Chỉ có nghĩa với poller; với Debezium theo dõi connector lag ở Kafka Connect |
| `table_dead_tuple_ratio` | gauge | `table` | Từ `pg_stat_user_tables`; bloat của `notification_delivery` |
| `inbox_unread_recompute_total` | counter | — | Redis miss/lệch; tăng đột biến = Redis flap |
| `ops_calls_total` | counter | `operation`, `result` | Ops API; audit chi tiết nằm ở `ops_audit` |

Mọi metric khai báo trong `internal/telemetry/metrics.go` với prefix `notification_`; `/metrics` nằm trên listener internal `8081`.

## 3. Alert

Gom từ [06-backpressure.md](./06-backpressure.md) §7 và [08-decide.md](./08-decide.md) §7, phân theo mức. Mọi alert có runbook ở [16-operations.md](./16-operations.md).

| Mức | Alert | Điều kiện | Runbook |
|---|---|---|---|
| **Page** | `CriticalLaneDelayed` | `delivery_pending_age_seconds{lane=critical} p99 > 30s` trong 2 phút | R-01 |
| **Page** | `ProviderBreakerOpen` | `circuit_breaker_state{dependency=ses} == 2` | R-02 |
| **Page** | `AuthContactBreakerOpen` | `circuit_breaker_state{dependency=auth_contact} == 2` | R-03 |
| **Page** | `PartitionRunway` | `partition_future_months < 1` | R-08 |
| **Page** | `ComplaintRateHigh` | `complaint_rate > 0.001` (0,1 %) | R-06 |
| **Page** | `SesDailyQuotaNearLimit` | `provider_daily_quota_used_ratio > 0.9` | R-05 |
| Ticket | `ProducerContractError` | `ingest_events_total{decision=FAILED, reason_code=~"unknown_template\|lane_mismatch\|invalid_template_data\|missing_dedup_key\|missing_sequence\|sequence_collision"} > 0` | R-04 |
| Ticket | `OrderNotificationOrderingLeaseStuck` | `ordering_lease_oldest_age_seconds{source="/services/order"} > 120` | R-15 |
| Ticket | `TransactionalLaneDelayed` | `pending_age{lane=transactional} p99 > 5m` | R-01 |
| Ticket | `BulkBacklogGrowing` | `delivery_pending_count{lane=bulk}` tăng liên tục 1 giờ hoặc > 1M | R-01 |
| Ticket | `BounceRateHigh` | `bounce_rate > 0.05` | R-06 |
| Ticket | `PossiblyDuplicateSpike` | `rate(delivery_possibly_duplicate_total[15m]) > 0.01 × rate(sent)` | R-07 |
| Ticket | `UnmatchedProviderEvents` | `provider_event_unmatched_age_seconds > 300` | R-09 |
| Ticket | `DlqNonEmptyCritical` | bất kỳ row DLQ nào ở lane critical | R-04 |
| Ticket | `FrequencyCapDegraded` | Redis lỗi, fail-open đang bật | R-10 |
| Ticket | `DeadTupleBloat` | `table_dead_tuple_ratio{table=notification_delivery} > 0.2` | R-11 |
| Info | `LocaleFallback` | `render_locale_fallback_total` tăng | — |
| Info | `ExpiredRateHigh` | `EXPIRED / DECIDED > 1 %` theo lane | Xem lại `max_age` hoặc lag |

Nguyên tắc: **page** chỉ khi user đang bị ảnh hưởng hoặc sắp mất dữ liệu; mọi thứ khác là ticket. Tổng số lỗi, DLQ bulk tăng, retry tăng — không page.

## 4. Trace

W3C Trace Context xuyên suốt, dùng `traceparent` từ CloudEvents extension (convention `tracing.md`):

```
[producer span] ──traceparent trong event──▶ [consumer: ingest+decide] ──lưu traceparent vào request──▶
   [deliver worker: claim] ── child ──▶ [auth.contact] ── child ──▶ [render] ── child ──▶ [ses.send]
                                                                                             │
   [track: sqs.receive] ── link tới span ses.send qua provider_message_id ──▶ [state transition] ──▶ [outbox]
```

- Consumer **tiếp tục** trace của producer (span parent = `traceparent` trong event) để một trace đi từ `checkout.submit` → `order.paid` → `notification.requested` → `ses.send`.
- Deliver worker chạy sau, không cùng ngữ cảnh: tạo span mới với **span link** tới trace gốc (đọc `traceparent` đã lưu trong `notification_request`).
- Track không có `traceparent` từ SES; span Track link tới span send qua attribute `provider_message_id`.
- Span attribute: như label metric + `delivery_id`, `request_id`, `template_version`, `attempt`, `provider_message_id`. Không email, không `template_data`.
- Sampling: 100 % cho lane critical và cho mọi span có lỗi; 10 % cho transactional; 1 % cho bulk. Tail-based nếu collector hỗ trợ.

## 5. Timeline một notification (công cụ support)

Câu hỏi hằng ngày nhất, và là thứ đáng đầu tư nhất. Nguồn: status API ([10-interfaces.md](./10-interfaces.md) §6) + `provider_event`. Một view/câu SQL trả lời trọn vẹn:

```sql
-- Toàn bộ lịch sử của đơn 1000234
SELECT r.received_at, r.source, r.template, r.decision, r.decision_reason,
       d.id AS delivery_id, d.channel, d.status, d.attempt, d.last_error_code,
       d.sent_at, d.delivered_at, d.finished_at, d.template_version, d.provider_message_id
FROM   notification_request r
LEFT JOIN notification_delivery d ON d.request_id = r.id
WHERE  r.subject = 'orders/1000234'
ORDER  BY r.received_at, d.channel;

-- Event provider của một delivery
SELECT received_at, event_type, status, occurred_at
FROM   provider_event
WHERE  provider_message_id = '0100018a-...'
ORDER  BY received_at;
```

Dashboard support (Grafana hoặc trang nội bộ) nhận `subject` hoặc `request_id`, hiển thị:

1. Request: nhận lúc nào, từ đâu, Decide nói gì (mã lý do → câu tiếng Việt, ví dụ `all_channels_off` → "User đã tắt email và in-app").
2. Từng delivery: kênh, dòng thời gian PENDING → SENDING → SENT → DELIVERED với timestamp, số lần thử, lỗi cuối, `template_version`.
3. Event provider theo thời gian.
4. Nút hành động (gọi ops API, có audit): redrive, xem suppression của user.

Không hiển thị email, không hiển thị `template_data`. Với câu hỏi "nội dung email là gì", render lại từ `template_version` + `template_data` qua `notifctl render` — đó là việc của engineer, không phải support.

## 6. Log

JSON có cấu trúc, một dòng một sự kiện, field chuẩn: `ts`, `level`, `msg`, `service`, `role`, `lane`, `trace_id`, `span_id`, `request_id`, `delivery_id`, `template`, `channel`, `stage`, `reason_code`, `attempt`, `provider_message_id`, `duration_ms`, `err`.

| Mức | Ghi gì | Không ghi gì |
|---|---|---|
| `info` | Chuyển trạng thái (`DECIDED`, `SENT`, `DELIVERED`, `FAILED` với lý do), khởi động/dừng thành phần, breaker đổi trạng thái, lane pause/resume, ops call (audit) | Từng lần claim thành công, từng token limiter |
| `warn` | Retryable error, lease expired, locale fallback, UNMATCHED lần đầu, Redis fail-open | |
| `error` | Permanent error, render_failed, invalid signature SNS, partition thiếu, migration lỗi | |
| `debug` | Chi tiết claim/batch, cache hit/miss | Tắt ở production |

Tuyệt đối không log: email (kể cả một phần), `template_data`, body request/response Auth, `raw` SES chưa hash, subject/body email đã render, OTP/URL reset. HTTP client tới Auth và SES tắt log body ở mọi mức ([11-privacy.md](./11-privacy.md) §3).

Audit log của ops API là stream riêng (`audit=true`), giữ theo chính sách audit của dự án, gồm: principal, thời điểm, operation, tham số (`delivery_id`, `lane`, `reason`), kết quả.

## 7. Dashboard

Ba dashboard, mỗi cái trả lời một câu hỏi của §1:

| Dashboard | Panel chính |
|---|---|
| **Lane health** (ops, luôn mở) | `pending_age` p99 theo lane (3 dòng, ngưỡng đỏ 30 s / 5 phút / 1 giờ); `pending_count` vs `scheduled_count`; `end_to_end` p99; `finished_total` theo `final_status`; consumer lag theo lane; lane state (paused?) |
| **Dependencies** (sự cố) | Error rate và p99 theo `dependency`; breaker state; bulkhead in-use/capacity theo lane; rate limiter tokens và wait; SES daily quota; SQS oldest age; retry budget |
| **Quality** (hằng tuần) | Bounce/complaint rate theo lane và template; `EXPIRED`/`SKIPPED` theo lý do; `possibly_duplicate`; locale fallback; render_failed theo template; top template theo volume |

Cộng **timeline support** ở §5 là công cụ riêng, không phải dashboard tổng hợp.

## 8. Health và readiness

| Endpoint | Ý nghĩa | Kiểm tra |
|---|---|---|
| `/livez` | Process sống | Không phụ thuộc gì |
| `/readyz` | Sẵn sàng nhận việc theo `ROLE` | Postgres ping; Redis ping (không bắt buộc với `deliver` — limiter fail-closed); Kafka metadata (`consumer`); template registry đã load; với `track`: SQS reachable |

Traefik không route health; mesh dùng readiness của Endpoints ([gateway design](../gateway/design.md)). Breaker mở với SES **không** làm readiness fail — service vẫn nhận request và xếp hàng, đó là hành vi mong muốn.
