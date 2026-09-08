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
| Create review p95 | < 400 ms |
| Aggregate convergence p95 | < 5 s |
| CDC publish p95 | < 3 s |
| Rating aggregate drift | 0 products in daily reconciliation |
| Privacy hard-delete overdue | 0 |
| Export-session creation/page error | 0 during active Search rebuild |

The aggregate target is now zero drift rather than a tolerated duplicate-delivery
percentage: `review_event_receipts` makes the normal aggregator path idempotent.

## 2. Health and dependencies

API readiness requires PostgreSQL only. Kafka/Connect outage must not make public
review commands unavailable: committed outbox rows wait for CDC. Consumer readiness
requires Kafka and PostgreSQL. Export-session creation additionally requires Kafka;
PDP/public reads do not.

## 3. Configuration

| Variable | Default | Notes |
|---|---:|---|
| `REVIEW_WINDOW_DAYS` | 30 | Review-create window after completion |
| `REVIEW_MAX_CONTENT_LENGTH` | 5000 | Cannot exceed schema hard cap without migration |
| `REVIEW_MAX_MEDIA_PER_REVIEW` | 9 | Attach-command validation |
| `REVIEW_MEDIA_UPLOAD_TTL` | 15m | `ISSUED` asset expiry |
| `REVIEW_EXPORT_SESSION_TTL` | 30m | Search must complete/restart before expiry |
| `REVIEW_EVENT_RECEIPT_RETENTION_DAYS` | >= Kafka retention + margin | Never purge earlier than replay window |
| `REVIEW_OUTBOX_RETENTION_DAYS` | 30 | Partition cleanup after connector proof |
| `REVIEW_REPORT_THRESHOLD` | 10 | Sensitive; never expose publicly |

## 4. Metrics and alerts

| Metric/alert | Meaning |
|---|---|
| `review_consumer_duplicate_total{consumer}` | Receipt conflict; expected under at-least-once delivery |
| `review_aggregate_drift_total` | Reconciliation found real drift; warning |
| `review_privacy_hard_delete_overdue_total` | Privacy request past deadline; critical |
| `review_export_session_total{result}` | Create/page/expiry outcomes |
| `review_export_session_active` | Active session count |
| `review_media_asset_pending_delete_total` | Storage cleanup backlog |
| Kafka Connect `FAILED`/`PAUSED` | Critical after two minutes |
| replication slot retained WAL bytes | Critical before disk-risk threshold |
| next outbox partition missing | Critical |

Do not publish `product_id`, `user_id`, `review_id` or `session_id` as metric labels.
Do not log review content, display names, reporter identity, legal takedown reason,
presigned URLs or direct object keys.

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

**Privacy overdue.** Stop treating it as a normal consumer DLQ. Find the request,
repair the failed batch/lock issue, rerun anonymize or purge transaction, then verify
direct customer IDs are null and request is `PURGED`.

**Media delete backlog.** Check storage worker failures and asset rows in
`DELETE_PENDING`; retry deletion. Public review access is already revoked by detaching
the asset before this worker runs.

## 7. Scheduled work

| Frequency | Work |
|---|---|
| 5 minutes | Expire eligibility/assets/sessions; alert privacy deadline; ensure next outbox partition |
| Daily | Process asset deletion and privacy purge; reconcile aggregate; evaluate partition cleanup |
| Continuous | Debezium, order consumer, privacy consumer, idempotent rating aggregator |
