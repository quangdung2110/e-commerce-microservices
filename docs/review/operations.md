---
doc_id: review-operations
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence: []
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
| Privacy hard-delete overdue | 0 |
| Export-session creation/page error | 0 during active Search rebuild |

The aggregate target is now zero drift rather than a tolerated duplicate-delivery
percentage: `review_event_receipts` makes the normal aggregator path idempotent.

## 2. Health and dependencies

API readiness requires PostgreSQL, configured `REVIEW_AUTHOR_ALIAS_HMAC_SECRET` and
`REVIEW_PRIVACY_GUARD_HMAC_SECRET`, and at least one existing `review_outbox` partition.
The partition is part of readiness rather than a background alert because every command
writes an outbox row: with no partition the instance cannot commit a single write, so it
must not receive traffic.
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
| `REVIEW_EVENT_RECEIPT_RETENTION_DAYS` | >= Kafka retention + margin | Never purge earlier than replay window |
| `REVIEW_OUTBOX_RETENTION_DAYS` | 30 | Partition cleanup after connector proof |
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

Ba giá trị còn lại không có biến môi trường riêng vì đổi chúng là đổi hành vi chứ không
phải tinh chỉnh vận hành, nên chúng nằm trong `application.yml`: allowlist MIME
(`review.media.image-content-types`, `review.media.video-content-types`), cron của hai
scheduled pass (`review.scheduler.lifecycle-cron` mặc định mỗi 5 phút,
`review.scheduler.daily-cron`) cùng `review.scheduler.sweep-batch-size`, và tên ba consumer
group Kafka (`review-order-sync`, `review-privacy-sync`, `review-rating-aggregator`) — đổi
tên group là reset vị trí đọc, và replay đó chỉ an toàn khi receipt tương ứng còn được giữ.

## 4. Metrics and alerts

| Metric/alert | Meaning |
|---|---|
| `review_consumer_duplicate_total{consumer}` | Receipt conflict; expected under at-least-once delivery |
| `review_aggregate_drift_total` | Reconciliation found real drift; warning |
| `review_privacy_hard_delete_overdue_total` | Privacy request past deadline; critical |
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
| `review_outbox_partition_missing` | 1 khi rolling window hai tháng không đủ; đồng thời readiness DOWN nếu không còn partition nào. Critical |

Do not publish `product_id`, `user_id`, `review_id` or `session_id` as metric labels.
Do not log review content, display names, reporter identity, legal takedown reason,
presigned URLs, direct object keys or notification `recipient_user_id`.

## 5. Outbox operational rule

`review_outbox` is an append-only CDC log. It has no `published_at`, and “number of
rows older than N minutes” is not backlog: retained, correctly published rows are old
too. Diagnose publish health from Kafka Connect status, source/slot LSN progress,
retained WAL and end-to-end event age measured at consumer.

Drop a monthly partition only after connector/slot evidence proves it has passed the
partition and retention elapsed. The scheduler must create current+next UTC partition;
missing it fails every command transaction because all commands write outbox.

## 6. Runbooks

**CDC failure.** Inspect connector error, slot LSN and retained WAL. Restart/fix the
connector; do not delete replication slot without an explicit decision because events
may be lost. Lock partition cleanup until recovery.

**Aggregate mismatch.** Confirm approved-review count/buckets, inspect consumer
receipts and malformed-event DLQ, then run product-scoped reconciliation. Repair must
increment `review_revision` and write metrics outbox snapshot.

**Export failure.** Search must not cut over. For 410/503, discard the current run,
restore Kafka/DB capacity if needed, create a new session and reimport all pages.

**Privacy overdue.** Stop treating it as a normal consumer DLQ. First read the failure:
a `check_violation` on one of the erasure pairings means the purge cleared an
identifier without its `*_erased_at`, or set `PURGED` without `purged_at`. That is a
code defect, not a transient fault, so retrying reproduces it exactly and the alert
never clears — fix the UPDATE to write both columns of each row before rerunning.
Otherwise find the request, repair the failed batch/lock issue, rerun anonymize or
purge transaction, then verify direct customer IDs are null, `subject_user_id` is null,
the HMAC guard remains and request is `PURGED`. If no request exists for a deletion known to Auth, Review cannot
discover that absence itself: Auth's existing direct publish may exhaust its three retries
and lose `USER_DELETED`. Follow Auth's alert/reconciliation/redrive process; do not
claim hard-delete completion before the event reaches Review. No Auth outbox is added.

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
| 5 minutes | Expire eligibility/export sessions; enqueue expired unattached `ISSUED`/`VERIFIED` assets as `DELETE_PENDING(UNATTACHED_EXPIRED)`; alert privacy deadline; ensure next outbox partition |
| Daily | Process every `DELETE_PENDING` asset (object-not-found is success), privacy purge, aggregate reconciliation and partition-cleanup evaluation |
| Continuous | Debezium, order consumer, privacy consumer, idempotent rating aggregator; Notification consumes Review request types independently |
