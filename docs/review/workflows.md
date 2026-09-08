---
doc_id: review-workflows
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/events/order/v1/asyncapi.yaml
  - contracts/events/auth/v1/asyncapi.yaml
verified_against_commit: null
---

# Review Service — Workflows

## Workflow index

| ID | Workflow |
|---|---|
| WF-REVIEW-001 | Grant entitlement from `ORDER_COMPLETED` |
| WF-REVIEW-002 | Issue/upload/verify/attach media asset |
| WF-REVIEW-003 | Create review |
| WF-REVIEW-004 | Edit review once |
| WF-REVIEW-005 | Public review and aggregate read |
| WF-REVIEW-006 | Admin reply |
| WF-REVIEW-007 | Report, hide and dismiss |
| WF-REVIEW-008 | Idempotent aggregate update |
| WF-REVIEW-009 | Search export session and replay |
| WF-REVIEW-010 | Privacy anonymize and hard purge |
| WF-REVIEW-011 | Reconciliation and lifecycle scheduler |
| WF-REVIEW-012 | Legal takedown and PDP summary |

## WF-REVIEW-001 — Grant entitlement

**Trigger:** `ORDER_COMPLETED` on `t.order.events`.

1. Validate CloudEvents `id`, order ID, buyer UUID, completion time and every
   item’s product/SKU; consume `variant_name` snapshot when supplied.
2. Begin transaction; insert receipt `(order-sync, id)`. Existing receipt means
   no-op and ACK.
3. Upsert one eligibility per `(order_id, product_id, sku_id)`. It is `PENDING` when
   `completed_at + review_window_days > now()`, otherwise `EXPIRED`.
4. Never overwrite a `CLAIMED` eligibility. Commit then ACK.

Missing product/SKU is contract failure and goes DLQ. Missing variant name is accepted
as null for old orders, but new producers must send it. No event leaves Review.

## WF-REVIEW-002 — Media asset lifecycle

**Issue.** `POST /media/upload-urls` creates an `ISSUED` asset owned by caller, with
opaque key and TTL, and returns `asset_id` plus presigned upload URL.

**Verify.** After client uploads, Review verifies object existence, MIME/type/size and
optional checksum before changing asset to `VERIFIED`. A request cannot attach an
`ISSUED`, expired, deleted or foreign asset.

**Attach.** Create/edit locks each asset and inserts `review_media`; `asset_id UNIQUE`
prevents double use. In the same transaction the asset becomes `ATTACHED` and review
`has_media` is updated.

**Detach/delete.** Replaced/taken-down media links are removed in the review
transaction and assets become `DELETE_PENDING`. A storage worker deletes bytes after
commit and marks `DELETED`; it retries safely. Direct storage calls never occur inside
the review transaction.

## WF-REVIEW-003 — Create review

**Trigger:** `POST /api/v1/reviews` with eligibility ID, rating, content and verified
asset IDs.

```text
BEGIN
  UPDATE eligibility
    SET status=CLAIMED, claimed_at=now()
    WHERE id=:id AND owner_user_id=:caller
      AND status=PENDING AND expires_at>now()
    RETURNING product_id, sku_id;
  INSERT review(..., edit_count=0, status=APPROVED);
  attach verified owned assets;
  INSERT outbox REVIEW_CREATED;
COMMIT
```

The conditional update is ownership check and lock. A zero-row result is classified
as 404/403/410/409. Unique review remains final race protection. Debezium failure
after commit delays downstream only; the review is already visible.

## WF-REVIEW-004 — Edit review once

**Trigger:** `PATCH /api/v1/reviews/{review_id}`.

Lock review and join eligibility to authorize owner. Require `status=APPROVED` and
`edit_count=0`. Save old rating/media presence, replace content/assets, set
`edit_count=1`, then write `REVIEW_UPDATED` carrying old/new metric values and
`affects_aggregate`. Content-only edit sets `affects_aggregate=false`.

Second edit returns `409 EDIT_QUOTA_EXHAUSTED`. A lost successful response is resolved
by reading review; retry must not apply an additional edit.

## WF-REVIEW-005 — Public reads

Review list queries only `APPROVED` rows with a keyset `(created_at, id)`. It batch
loads `review_media` and `review_admin_replies`; it generates read URLs from attached,
non-deleted assets. It never returns report identity/count, eligibility or legal reason.

Aggregate read returns zero if aggregate row is absent. PostgreSQL errors return 503,
not synthetic zero. Author read-after-write routes to primary if replicas are used.

## WF-REVIEW-006 — Admin reply

Lock review and require `APPROVED`. Insert/update one reply; preserve both
`created_by_admin_id` and `updated_by_admin_id`. First insert writes
`REVIEW_REPLY_NOTIFICATION_REQUESTED` in the same transaction when the eligibility
owner ID has not been purged; it carries only review/product/recipient IDs. Later
content update does not notify again. Reply state has no denormalized boolean on review.

## WF-REVIEW-007 — Report, hide and dismiss

**Report:** lock review, reject author/hidden/removed review, insert report and
increase `active_report_count`. If threshold is reached, set review hidden, transition
all pending reports to `ACTIONED`, and write one `REVIEW_HIDDEN` event.

**Dismiss:** admin locks review, changes its `ACTIONED`/`PENDING` reports to
`DISMISSED`, recomputes active count, restores review to `APPROVED` and writes
`REVIEW_APPROVED`. New reports after dismissal start fresh. The partial unique index
prevents duplicate report while reporter identity exists.

## WF-REVIEW-008 — Idempotent aggregate update

**Trigger:** `REVIEW_CREATED`, aggregate-affecting `REVIEW_UPDATED`,
`REVIEW_HIDDEN`, `REVIEW_APPROVED`, `REVIEW_REMOVED`.

1. In one transaction insert receipt `(review-rating-aggregator, event_id)`.
2. Conflict means event was already applied: commit no-op and ACK.
3. Group valid new events by `product_id`; apply bucket deltas atomically.
4. Derive total/average from bucket SQL, increase `review_revision`, write one
   `REVIEW_METRICS_UPDATED` outbox snapshot per affected product.
5. Commit then ACK.

Content-only update, `REVIEW_METRICS_UPDATED`,
`REVIEW_REPLY_NOTIFICATION_REQUESTED` and `REVIEW_REMOVAL_NOTIFICATION_REQUESTED`
are acknowledged without an aggregate effect. Constraint failure or malformed delta
rolls back receipt and aggregate; message retries/DLQ rather than silently producing
bad metrics.

## WF-REVIEW-009 — Search export session

**Create:** Search calls `POST /internal/v1/reviews/rating-aggregate-exports`.
Review authorizes Search identity, asks Kafka for end offset of each current
`t.review.events` partition, and persists one `rating_export_sessions` row plus all
`rating_export_session_offsets` rows. It returns 201 with ID, expiry and offsets.

**Page:** Search calls `GET .../{session_id}?after_product_id=...`. Review checks
session active/not expired, scans `product_rating_aggregates` by keyset and echoes the
unchanged offsets. A 404 means unknown ID; 410 means session expired.

**Replay:** Search imports all pages, seeks every saved offset inclusive and replays
metrics events. An event that overlaps snapshot is no-op when its revision is not
greater. Any page failure or expiry discards the whole run and starts a new session.

Kafka partition count/key mapping is operationally immutable during a session. If it
changes, Search aborts and starts a new export after topology stabilizes.

## WF-REVIEW-010 — Privacy anonymize and hard purge

**Trigger:** `USER_DELETED`.

1. Store `(privacy-sync, event_id)` receipt and `privacy_erasure_requests` row.
2. Update the subject’s review display names and `author_anonymized_at`. Do not emit a
   Review event or touch aggregate/revision: each PII-owning downstream service gets
   `USER_DELETED` directly from Auth.
3. Mark request `ANONYMIZED`; ACK only after commit.
4. At `hard_delete_after`, a worker locks the request and nulls customer owner IDs in
   eligibility/media assets and reporter IDs in reports, then clears request
   `subject_user_id` and marks it `PURGED` in one transaction.

Reviews, rating, content and opaque asset keys remain. Admin/legal identity retention
is not altered by customer-erasure worker. A failed privacy request alerts at critical
severity and retries; it must not silently DLQ.

## WF-REVIEW-011 — Scheduler/reconciliation

Every five minutes: expire `PENDING` eligibility, expire unattached assets, expire
export sessions, alert on privacy deadline and create outbox partition for the next
UTC month.

Daily: process asset `DELETE_PENDING`, privacy hard purge, compare aggregate with
approved reviews, and repair drift with a revision increment plus metric outbox event.
Aggregate replay duplicates should no longer cause drift; reconciliation remains a
corruption/bug safety net.

Outbox cleanup drops a partition only after Kafka Connect and replication-slot
telemetry prove all changes passed its range. It never counts old outbox rows as
“unpublished backlog”.

## WF-REVIEW-012 — Takedown and PDP summary

**Takedown:** lock review, validate legal case and two-person approval, insert the one
audit record, set review `REMOVED`, detach assets to `DELETE_PENDING`, write
`REVIEW_REMOVED(was_counted)` and commit. If eligibility owner identity remains,
write `REVIEW_REMOVAL_NOTIFICATION_REQUESTED` in the same transaction. Notification
receives only review/product/recipient IDs; it never receives legal `reason`, case or
admin identity.

**PDP:** Product Detail BFF calls its existing internal read endpoint. Review returns
aggregate plus bounded approved preview, without eligibility, reports, legal state or
review revision. Database failure returns 503 so BFF can expose `UNAVAILABLE` rather
than fake an empty review section.
