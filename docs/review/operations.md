---
doc_id: review-operations
service: review
scope: current
status: implemented
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - docs/review/schema/0002_eligibility_product_snapshot.sql
  - docs/review/schema/0003_privacy_sweep_includes_failed.sql
  - contracts/http/review/v1/openapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
  - contracts/events/review/v1/asyncapi.yaml
  - java/services/review-service/src/main/java/com/ecommerce/review
  - java/services/review-service/src/test/java/com/ecommerce/review
  - java/services/review-service/src/main/resources/application.yml
  - java/services/review-service/src/main/resources/debezium/review-postgres-connector.json
  - docker-compose.yml
verified_against_commit: null
---

# Review Service — Vận hành

## 1. Proposed SLOs

| SLI | Target |
|---|---:|
| Public review/PDP read availability | 99.9% monthly |
| Public read p95, 20 rows | < 200 ms |
| Internal PDP summary p99, preview ≤ 5 | < 200 ms (Product Detail BFF cắt ở 300 ms, [ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md)) |
| Create review p95 | < 400 ms |
| Aggregate convergence p95 | < 5 s |
| CDC publish p95 | < 3 s |
| Rating aggregate drift | 0 products in daily reconciliation |
| Privacy hard-delete overdue | 0 after each daily pass |
| Export-session creation/page error | 0 during active Search rebuild |

The aggregate target is now zero drift rather than a tolerated duplicate-delivery
percentage: `review_event_receipts` makes the normal aggregator path idempotent.

The purge runs once a day (02:15), so a request is legitimately past its deadline from the
moment it matures until the next pass. "Overdue 0" is therefore measured across a whole pass,
and the critical alert on `review_privacy_hard_delete_overdue_total` fires when it stays above
zero for more than 25 hours, not on the first non-zero sample.

## 2. Health and dependencies

API readiness requires PostgreSQL, configured `REVIEW_AUTHOR_ALIAS_HMAC_SECRET` and
`REVIEW_PRIVACY_GUARD_HMAC_SECRET`, and the `review_outbox` partition of the **current UTC
month**. The partition is part of readiness rather than a background alert because every
command writes an outbox row into it: without it the instance cannot commit a single write, so
it must not receive traffic. Old partitions do not count — they are never dropped
automatically, so a count of partitions cannot tell history from the window. A missing
partition for the *next* month does not fail readiness, since writes still land in the current
month, but it raises `review_outbox_partition_missing`.
Kafka/Connect outage must not make public review commands unavailable: committed outbox rows wait for CDC. Consumer readiness
requires Kafka and PostgreSQL. Export-session creation additionally requires Kafka;
PDP/public reads do not. Media upload-instruction issuance and create/edit containing
media additionally require the storage signer/metadata read; outage there returns a
retryable 503 for that media operation and never attaches an unchecked object.

## 3. Configuration

| Variable | Default | Notes |
|---|---:|---|
| `REVIEW_WINDOW_DAYS` | 30 | Review-create window after completion |
| `REVIEW_MAX_CONTENT_LENGTH` | 5000 | Cannot exceed schema hard cap without migration |
| `REVIEW_MAX_MEDIA_PER_REVIEW` | 9 | Attach-command validation |
| `REVIEW_MEDIA_UPLOAD_TTL` | 15m | Expiry of the signed upload instruction and, in v1, attachment deadline for an `ISSUED`/`VERIFIED` asset; expiry queues storage cleanup rather than leaving a media `EXPIRED` state |
| `REVIEW_MEDIA_PUBLIC_URL_TTL` | 5m | TTL của signed download URL trên public/owner read |
| `REVIEW_MEDIA_ADMIN_URL_TTL` | 2m | TTL của signed download URL trên `/api/v1/admin/**`. Startup từ chối cấu hình nếu giá trị này không ngắn hơn TTL public |
| `REVIEW_MAX_UNATTACHED_ASSETS_PER_USER` | 18 | Quota cho asset `ISSUED`/`VERIFIED`; bắt buộc để direct upload không thành unlimited storage |
| `REVIEW_MAX_UNATTACHED_BYTES_PER_USER` | 512 MiB | Tổng byte đã khai báo của asset `ISSUED`/`VERIFIED` theo user. Quota số lượng một mình không đủ: 18 slot video là lượng storage hoàn toàn khác 18 thumbnail |
| `REVIEW_MAX_IMAGE_BYTES` | 10 MiB | Enforce trong signed upload policy và metadata verification |
| `REVIEW_MAX_VIDEO_BYTES` | 100 MiB | Enforce trong signed upload policy và metadata verification |
| `REVIEW_EXPORT_SESSION_TTL` | 30m | Search must complete/restart before expiry |
| `REVIEW_EVENT_RECEIPT_RETENTION_DAYS` | 30 | Receipts older than this are deleted by the daily pass. Startup rejects anything below 14, the Kafka retention of `t.review.events`; the real value must exceed the replay horizon of the environment |
| `REVIEW_REPORT_THRESHOLD` | 10 | Sensitive; never expose publicly |
| `REVIEW_AUTHOR_ALIAS_HMAC_SECRET` | none | Required, stable v1 secret for author pseudonym without direct PII; secret rotation changes aliases for future reviews |
| `REVIEW_PRIVACY_GUARD_HMAC_SECRET` | none | Required, stable secret for HMAC deletion guards. Do not rotate while a guard with its key version is retained. |
| `REVIEW_INTERNAL_PDP_TOKENS` | none | Bearer token allowlist cho `/internal/v1/reviews/products/**`. Rỗng nghĩa là từ chối mọi caller |
| `REVIEW_INTERNAL_EXPORT_TOKENS` | none | Bearer token allowlist cho `/internal/v1/reviews/rating-aggregate-exports**`. Tách khỏi PDP để Product Detail BFF không phân trang được rating export và Search không đọc được PDP summary |
| `REVIEW_S3_ENDPOINT` / `REVIEW_S3_BUCKET` / `REVIEW_S3_ACCESS_KEY` / `REVIEW_S3_SECRET_KEY` | none | Object storage S3-compatible cho media bytes; đổi provider là thay cấu hình, không phải đổi contract |
| `REVIEW_S3_PUBLIC_ENDPOINT` | empty → `REVIEW_S3_ENDPOINT` | Origin storage mà trình duyệt truy cập được; presigner ký trực tiếp origin này cho upload/download. S3 client HEAD/DELETE vẫn dùng endpoint nội bộ. Compose local: `http://localhost:9000`; không rewrite host của signed URL |
| `REVIEW_S3_REGION` | `us-east-1` | Region dùng để ký SigV4; MinIO chấp nhận bất kỳ giá trị nào miễn là ổn định |
| `REVIEW_S3_PATH_STYLE` | true | Path-style addressing. MinIO và phần lớn S3 server tự host không phục vụ virtual-host bucket |
| `REVIEW_SCHEDULER_ENABLED` | true | Tắt hai scheduled pass. Chỉ dùng cho test và cho instance chạy read-only; môi trường thật phải có ít nhất một instance bật, nếu không partition outbox và cleanup ngừng chạy |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DB_POOL_SIZE` | none / none / none / 16 | PostgreSQL. `DB_URL` phải mang `currentSchema=review,public` để enum cast và relation không định danh giải đúng schema Review sở hữu |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Cluster cho ba consumer và cho lần chụp offset của export session |
| `KAFKA_LISTENER_CONCURRENCY` | 3 | Số thread mỗi listener (profile `prod`; `spring.kafka.listener.concurrency`) |
| `SERVER_PORT` | 8080 | Cổng HTTP của service |

Các giá trị sau không có biến môi trường riêng vì đổi chúng là đổi hành vi chứ không
phải tinh chỉnh vận hành, nên chúng nằm trong `application.yml`: allowlist MIME
(`review.media.image-content-types`, `review.media.video-content-types`), cron của hai
scheduled pass (`review.scheduler.lifecycle-cron` mặc định mỗi 5 phút,
`review.scheduler.daily-cron` mặc định 02:15) cùng `review.scheduler.sweep-batch-size`
(kích thước một trang của sweep, mặc định 500; mỗi lần chạy vẫn duyệt hết các trang), tên ba
consumer group Kafka (`review-order-sync`, `review-privacy-sync`, `review-rating-aggregator`)
— đổi tên group là reset vị trí đọc, và replay đó chỉ an toàn khi receipt tương ứng còn được
giữ — tên topic (`review.topics.*`) và danh tính Gateway: tên hai header
`review.gateway.user-id-header` / `roles-header` (`X-User-Id`, `X-User-Roles`) và role admin
`review.gateway.admin-role` (`ADMIN`, so sánh không phân biệt hoa thường với từng phần tử của
`X-User-Roles`).

Review không có cấu hình retention cho `review_outbox`: nó không drop partition (mục 5).

## 4. Metrics and alerts

| Metric/alert | Meaning |
|---|---|
| `review_consumer_duplicate_total{consumer}` | Receipt conflict; expected under at-least-once delivery |
| `review_aggregate_drift_total` | Reconciliation found real drift; warning |
| `review_privacy_hard_delete_overdue_total` | Gauge: privacy requests past `hard_delete_after` that are not `PURGED`, `FAILED` included. Critical when above zero for more than 25 hours (mục 1) |
| `review_privacy_consumer_failure_total` | One failed delivery of a `USER_DELETED` record. The listener retries without a time limit, so a rate that does not return to zero is a deletion that is stuck, and it has no privacy request row for the gauge above to count. Critical when it keeps increasing for 5 minutes |
| `review_privacy_guard_blocked_total{operation}` | Guard suppressed a late Order event, rejected a customer write, or suppressed the notification branch of an admin reply/takedown; `operation` is a bounded operation name, never a user ID |
| `review_export_session_total{result}` | Create/page/expiry outcomes |
| `review_export_session_active` | Active session count |
| `review_media_asset_pending_delete_total` | Storage cleanup backlog |
| `review_media_cleanup_total{reason,result}` | Asset cleanup transitions/results; `reason` is only `UNATTACHED_EXPIRED`, `REPLACED` or `TAKEDOWN`, and `result` is bounded (including `object_not_found_success`) |
| `review_media_verification_total{result,media_type}` | Object missing/type-size-checksum mismatch hoặc attach verification thành công; không gắn user/asset ID làm label |
| `review_media_unattached_quota_rejected_total` | Gateway/API đã chặn cấp thêm upload asset vì quota |
| `review_notification_request_total{type,result}` | First-reply/removal request written or intentionally skipped because the recipient has a deletion guard |
| Kafka Connect `FAILED`/`PAUSED` | Critical after two minutes |
| replication slot retained WAL bytes | Critical before disk-risk threshold |
| `review_outbox_partition_missing` | 1 khi partition của tháng UTC hiện tại hoặc tháng kế tiếp thiếu; readiness DOWN khi thiếu tháng hiện tại. Critical |

Do not publish `product_id`, `user_id`, `review_id` or `session_id` as metric labels.
Do not log review content, display names, reporter identity, legal takedown reason,
presigned URLs, direct object keys or notification `recipient_user_id`.

## 5. Outbox operational rule

`review_outbox` is an append-only CDC log. It has no `published_at`, and “number of
rows older than N minutes” is not backlog: retained, correctly published rows are old
too. Diagnose publish health from Kafka Connect status, source/slot LSN progress,
retained WAL and end-to-end event age measured at consumer.

The scheduler must create current+next UTC partition; missing it fails every command
transaction because all commands write outbox.

Review never drops a partition. Old months accumulate until an operator removes them, once
per month at most:

1. Only a month that has ended can be dropped; writes land in the current month's partition.
2. Record `SELECT pg_current_wal_lsn()` after the month ended, then wait until the connector
   slot's `confirmed_flush_lsn` (`pg_replication_slots`, slot `review_outbox_slot`) is at or
   beyond it and Kafka Connect reports the connector `RUNNING`. Every change to that partition
   is older than the recorded LSN, so the connector has then published all of them.
3. Decide how long to keep it: Review itself never reads the table back, so this is only the
   window in which an operator may want to re-publish from the outbox. Keeping it for at
   least the 14-day retention of `t.review.events` is a sensible floor.
4. `ALTER TABLE review.review_outbox DETACH PARTITION review.review_outbox_yYYYYmMM;` then
   `DROP TABLE review.review_outbox_yYYYYmMM;`.

Never drop the current or the next month, and never drop one while the connector is
`FAILED`/`PAUSED` or the slot has not caught up.

## 6. Runbooks

**CDC failure.** Inspect connector error, slot LSN and retained WAL. Restart/fix the
connector; do not delete replication slot without an explicit decision because events
may be lost. Lock partition cleanup until recovery.

**Aggregate mismatch.** Confirm approved-review count/buckets, inspect consumer
receipts and malformed-event DLQ, then run product-scoped reconciliation. Repair must
increment `review_revision` and write metrics outbox snapshot. A difference found within
seconds of a write is usually aggregator lag, not drift: recount once the consumer has caught
up before repairing. The daily reconciliation has the same blind spot — it can count a review
whose event is still in flight twice — and the next run corrects it
([ADR-005](./decisions/ADR-005-async-rating-aggregation.md)).

**Export failure.** Search must not cut over. For 410/503, discard the current run,
restore Kafka/DB capacity if needed, create a new session and reimport all pages.

**Privacy overdue.** Stop treating it as a normal consumer DLQ. A purge that fails marks its
request `FAILED` (`failure_detail` and an `error` log name it) and is retried by every daily
pass, so a transient cause clears by itself; the gauge stays up until the request is `PURGED`.
A request that is still `FAILED` after two passes is not transient. First read the failure:
a `check_violation` on one of the erasure pairings means the purge cleared an
identifier without its `*_erased_at`, or set `PURGED` without `purged_at`. That is a
code defect, not a transient fault, so retrying reproduces it exactly and the alert
never clears — fix the UPDATE to write both columns of each row before rerunning.
Otherwise find the request, repair the lock or connectivity issue behind it, let the next
daily pass purge it, then verify direct customer IDs are null, `subject_user_id` is null,
the HMAC guard remains and request is `PURGED`. If no request exists for a deletion known to Auth, Review cannot
discover that absence itself: Auth's existing direct publish may exhaust its three retries
and lose `USER_DELETED`. Follow Auth's alert/reconciliation/redrive process; do not
claim hard-delete completion before the event reaches Review. No Auth outbox is added.

**`USER_DELETED` stuck.** `review_privacy_consumer_failure_total` keeps rising and the
`review-privacy-sync` group's lag on `t.auth.events` does not fall. The listener retries the
record forever (backoff capped at one minute) and never dead-letters it for a transient
fault, so the stall is Review's to fix: read the `error` log of the failing delivery, repair
the dependency (usually PostgreSQL) and the listener resumes by itself. Do not skip the record
or reset the group's offset: that is exactly the lost deletion this behaviour prevents. Only a
record that violates its contract reaches `t.auth.events.dlq`, immediately; that DLQ is shared
with other consumers of `t.auth.events`, so filter by payload before redriving.

**Outbox partition missing (readiness `DOWN`, or `review_outbox_partition_missing` = 1).** The
five-minute pass creates the current and next month. If it did not, check that at least one
instance runs with `REVIEW_SCHEDULER_ENABLED=true`; to recover at once, create the partition
by hand: `CREATE TABLE review.review_outbox_y2026m11 PARTITION OF review.review_outbox FOR
VALUES FROM ('2026-11-01') TO ('2026-12-01');` with the month's own name and bounds.

**Media delete backlog.** Check storage worker failures and asset rows in
`DELETE_PENDING`, including `deletion_reason` and `delete_requested_at`; retry deletion
without changing state manually. Treat a storage “object not found” result as success
and let the worker mark `DELETED`: an `UNATTACHED_EXPIRED` asset may never have been
uploaded. Public review access is already revoked by detaching a replacement/takedown
asset before this worker runs. If expiry cleanup has no progress, inspect the five-minute
sweep for `ISSUED`/`VERIFIED` rows with `expires_at <= now()` and its row-lock errors.

**Media verification failure.** Do not attach or manually mark an asset `VERIFIED`.
Check signed-upload expiry, one-write/no-overwrite policy, object existence and actual
MIME/byte-size/checksum versus the issue request. Let caller upload a new asset if the
existing object is invalid; retry only a transient signer/metadata-read failure.

## 7. Scheduled work

| Frequency | Work |
|---|---|
| 5 minutes | Expire eligibility/export sessions; enqueue expired unattached `ISSUED`/`VERIFIED` assets as `DELETE_PENDING(UNATTACHED_EXPIRED)`; refresh the gauges (privacy overdue, pending media, active sessions, missing partition); ensure the current and next outbox partition |
| Daily (02:15) | Process every `DELETE_PENDING` asset (object-not-found is success), privacy purge (`FAILED` requests are retried), aggregate reconciliation, and deletion of event receipts older than `REVIEW_EVENT_RECEIPT_RETENTION_DAYS`. Each sweep pages through its whole backlog; none of them drops an outbox partition |
| Continuous | Debezium, order consumer, privacy consumer, idempotent rating aggregator; Notification consumes Review request types independently |

## Request ID, trace ID and caller deadline

Per [contracts ADR-002](../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md) and [ADR-003](../../contracts/decisions/ADR-003-deadline-propagation.md), plus [ADR-014](./decisions/ADR-014-request-correlation-and-caller-deadline.md). Log lines written
while a request is served carry `request_id` and `trace_id` (put in the MDC by `RequestCorrelationFilter` and shown by
`logging.pattern.level` (dev/test) and `logback-spring.xml` (prod JSON)); background work has neither. A missing or malformed `X-Request-Id` is replaced, never a `400`,
and the effective one is returned in the response.

The counter `http.server.caller_deadline_exceeded{route}` counts requests that finished after the caller
stopped waiting (`X-Request-Timeout-Ms`), with a WARN line `request outlived the caller deadline`. A steady
rise means Review answers slower than its callers' timeouts: look at the route's p99 first, then at the
caller's timeout. The alert threshold is set from load-test p99, not in advance. The outbox `correlationid` and the Problem `trace_id` still come from `RequestContext` and are unchanged.
