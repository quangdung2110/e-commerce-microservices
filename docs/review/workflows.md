---
doc_id: review-workflows
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - contracts/http/review/v1/openapi.yaml
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
2. Derive the buyer's HMAC privacy subject key. Begin transaction, take that
   transaction-scoped subject lock, then insert receipt `(order-sync, id)`. Existing
   receipt means no-op and ACK.
3. If a privacy request for the subject key exists in any state, commit the receipt,
   increment the bounded suppressed-event metric and ACK without creating an
   eligibility. Otherwise, for every item calculate
   `expires_at = completed_at + review_window_days` from
   the event's business timestamp. Insert one eligibility per
   `(order_id, product_id, sku_id)` with `ON CONFLICT DO NOTHING`: an existing
   business-key row is never overwritten. Set it `PENDING` when `expires_at > now()`;
   otherwise set it `EXPIRED`.
4. Commit then ACK.

Missing product/SKU is contract failure and goes DLQ. Missing variant name is accepted
as null for old orders, but new producers must send it. `created_at` records ingestion
only; it never extends the review window. `now()` is the transaction timestamp. No
event leaves Review.

## WF-REVIEW-002 — Media asset lifecycle

**Issue.** `POST /media/upload-urls` creates an `ISSUED` asset owned by caller, with
opaque key and TTL, and returns `asset_id` plus a provider-neutral signed upload
instruction. The object key never appears in the HTTP response. Issuing is bounded by
per-review, per-user-unattached and byte quotas. In its mutation transaction Review
takes the caller's privacy subject lock and rejects an existing deletion guard before
it writes `owner_user_id`. For a caller without a guard, ownership is unchanged:
`owner_user_id = caller`.

**Verify and attach.** Client has no verify/poll API. When it sends asset IDs in
create/edit, Review reads each immutable object metadata before its database
transaction and verifies existence, MIME/type, size and optional checksum. The
transaction first takes the caller's subject lock and rejects a deletion guard, then
locks the asset, transitions it through `VERIFIED` to `ATTACHED` and adds the media
link. A missing, expired, invalid, foreign or already attached asset is rejected; a
signed upload must be single-write/no-overwrite to prevent post-check byte replacement.

**Attach.** Create/edit locks each asset and inserts `review_media`; `asset_id UNIQUE`
prevents double use. In the same transaction the asset becomes `ATTACHED` and review
`has_media` is updated.

**Detach/delete.** Replaced media links are removed in the review transaction and
assets become `DELETE_PENDING` with `deletion_reason=REPLACED`; legal takedown uses
`TAKEDOWN`. A storage worker deletes bytes after commit and marks `DELETED` only after
Storage confirms deletion. It treats “object not found” as a successful deletion, so
an expired asset that was never uploaded does not retry forever. Direct storage calls
never occur inside the review transaction.

## WF-REVIEW-003 — Create review

**Trigger:** `POST /api/v1/reviews` with eligibility ID, rating, content and uploaded
asset IDs.

Review derives `author_display_name` from the authenticated `X-User-Id` before any
storage/DB work: `Khách hàng #` plus the first 12 uppercase Base32 characters of
`HMAC-SHA-256("review-author-alias:v1:" || canonical_lowercase_uuid(caller_id))`.
It never accepts a profile name/email or calls User. Missing trusted caller identity
remains `401` at Gateway/API boundary; the alias is display-only, never authorization.

```text
VERIFY submitted immutable object metadata before BEGIN
BEGIN
  take caller privacy subject lock; reject if a deletion guard exists;
  lock and transition each valid caller-owned asset ISSUED -> VERIFIED -> ATTACHED;
  UPDATE eligibility
    SET status=CLAIMED, claimed_at=now()
    WHERE id=:id AND owner_user_id=:caller
      AND status=PENDING AND expires_at>now()
    RETURNING product_id, sku_id;
  INSERT review(..., author_display_name=:derived_author_pseudonym,
                edit_count=0, status=APPROVED);
  attach the locked assets;
  INSERT outbox REVIEW_CREATED;
COMMIT
```

The object check causes no storage write; the signed upload is already complete before
the database transaction starts. The conditional update is ownership check and lock. A
zero-row result is classified as 404/403/410/409. Unique review remains final race
protection. Debezium failure after commit delays downstream only; the review is already
visible.

## WF-REVIEW-004 — Edit review once

**Trigger:** `PATCH /api/v1/reviews/{review_id}`.

Review takes the caller's privacy subject lock and rejects a deletion guard before it
locks review and joins eligibility to authorize owner. Require `status=APPROVED` and
`edit_count=0`. Review preserves fields absent from PATCH; `content: null` clears text,
an absent `media_asset_ids` preserves attachments and an empty array removes all of
them. It verifies any newly supplied object metadata before its transaction, then
saves old rating/media presence, replaces requested assets, sets `edit_count=1`, and
writes `REVIEW_UPDATED` carrying old/new metric values and `affects_aggregate`.
Content-only edits and media replacement retaining `has_media=true` set
`affects_aggregate=false`.

Second edit returns `409 EDIT_QUOTA_EXHAUSTED`. A lost successful response is resolved
by reading review; retry must not apply an additional edit.

## WF-REVIEW-005 — Public reads

Review list queries only `APPROVED` rows with a keyset `(created_at, id)`. It batch
loads `review_media` and `review_admin_replies`; it generates read URLs from attached,
non-deleted assets. It never returns report identity/count, eligibility or legal reason.

Public and owner reads issue no signed URL for media of a review that is not
`APPROVED`. Only `/api/v1/admin/**` does, with a shorter TTL, so moderation can see
what was reported.

Aggregate read returns zero if aggregate row is absent. PostgreSQL errors return 503,
not synthetic zero. Author read-after-write routes to primary if replicas are used.

## WF-REVIEW-006 — Admin reply

**Trigger:** `PUT /api/v1/admin/reviews/{review_id}/reply`.

Lock review and require `APPROVED`. Insert/update one reply; preserve both
`created_by_admin_id` and `updated_by_admin_id`. First insert writes
`REVIEW_REPLY_NOTIFICATION_REQUESTED` in the same transaction when the eligibility
owner ID has not been purged; it carries only review/product/recipient IDs. Later
content update does not notify again. Reply state has no denormalized boolean on review.

## WF-REVIEW-007 — Report, hide and dismiss

**Report (`POST /api/v1/reviews/{review_id}/reports`):** take the caller's privacy
subject lock and reject a deletion guard, then lock review, reject author/hidden/removed
review, insert report and increase `active_report_count`. If threshold is reached, set
review hidden, set `hidden_at=now()`, transition all pending reports to `ACTIONED`, and
write one `REVIEW_HIDDEN` event.

**Find the case (`GET /api/v1/admin/reviews?status=HIDDEN_BY_REPORT`):** hiding a
review moves all of its `PENDING` reports to `ACTIONED`, so the case disappears from
the `PENDING` triage queue at the moment it starts needing a decision. This list is the
only supported way to obtain the `review_id` that dismissal requires, and therefore the
only way `HIDDEN_BY_REPORT → APPROVED` can ever be taken. It reads
`ix_review_hidden_queue` with a keyset on `(hidden_at, id)`, oldest-hidden first, then
batch loads each review's media and its undismissed reports. Reporter identity is never
returned. Signed media URLs are issued here even though the review is hidden; that
exception exists only on the admin surface, because a report about an image cannot be
judged without seeing it.

**Dismiss (`POST /api/v1/admin/reviews/{review_id}/report-dismissals`):** admin locks
review, changes its `ACTIONED`/`PENDING` reports to
`DISMISSED` and recomputes active count. It restores only a `HIDDEN_BY_REPORT` review
to `APPROVED` and writes `REVIEW_APPROVED`; a terminal `REMOVED` review stays removed.
`hidden_at` is deliberately left in place so a repeatedly brigaded review keeps its
history; every index over it is partial on status, so the stale value is inert.
New reports after dismissal start fresh. The partial unique index prevents duplicate
active report while reporter identity exists.

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

1. Derive the HMAC privacy subject key, begin a transaction and take its subject lock.
   Store `(privacy-sync, event_id)` receipt and insert `privacy_erasure_requests` with
   `ON CONFLICT (subject_match_key) DO NOTHING`. A logical duplicate deletion with a
   new event ID only commits its receipt and changes nothing else.
2. Update the subject’s review display names and `author_anonymized_at`. Do not emit a
   Review event or touch aggregate/revision: each PII-owning downstream service gets
   `USER_DELETED` directly from Auth.
3. Mark request `ANONYMIZED`; ACK only after commit. From that commit onward, Order
   input is suppressed and customer upload/create/edit/report writes for the subject
   are rejected by the same guard.
4. At `hard_delete_after`, a worker takes the same subject lock, nulls customer owner
   IDs in eligibility/media assets and reporter IDs in reports, then clears request
   `subject_user_id` and marks it `PURGED` in one transaction. It retains the HMAC
   subject key to suppress late/replayed input without retaining a direct identifier.

Reviews, rating, content and opaque asset keys remain. Admin/legal identity retention
is not altered by customer-erasure worker. A failed privacy request alerts at critical
severity and retries; it must not silently DLQ. Auth deliberately retains its current
direct post-commit publish with at most three retries: a `USER_DELETED` lost before Review
receives it creates no guard. Review cannot detect that absence; Auth operations must
reconcile/redrive the event, without adding an Auth outbox.

Guard rejection uses the existing generic authentication/authorization response; it
does not add a public `ACCOUNT_DELETED` error or expose deletion state to a caller.

## WF-REVIEW-011 — Scheduler/reconciliation

Every five minutes: atomically transition every `PENDING` eligibility with
`expires_at <= now()` to `EXPIRED`; transition every `ISSUED` or `VERIFIED` asset with
`expires_at <= now()` to `DELETE_PENDING`, setting
`deletion_reason=UNATTACHED_EXPIRED` and `delete_requested_at`; expire export sessions;
alert on privacy deadline; and create the outbox partition for the next UTC month.
The asset sweep locks each candidate asset row and performs a conditional state
transition. It races safely with attach: attach that commits `ATTACHED` first removes
the row from the sweep; a sweep that commits `DELETE_PENDING` first makes later attach
fail as unusable. No storage operation happens in this scheduler transaction.

Daily: process every asset `DELETE_PENDING` regardless of reason; delete its
`object_key` and only then mark it `DELETED`. “Object not found” is idempotent success;
transient or definitive storage failures leave it `DELETE_PENDING` for safe retry and
alerting. Also run privacy hard purge, compare aggregate with approved reviews, and
repair drift with a revision increment plus metric outbox event. Aggregate replay
duplicates should no longer cause drift; reconciliation remains a corruption/bug
safety net.

Outbox cleanup drops a partition only after Kafka Connect and replication-slot
telemetry prove all changes passed its range. It never counts old outbox rows as
“unpublished backlog”.

## WF-REVIEW-012 — Takedown and PDP summary

**Takedown (`POST /api/v1/admin/reviews/{review_id}/takedown`):** lock review, validate
legal case/reason and the caller's admin role, then
insert the one audit record with `taken_down_by_admin_id` from trusted Gateway
identity. Set review `REMOVED`, detach assets to `DELETE_PENDING` with
`deletion_reason=TAKEDOWN`, write
`REVIEW_REMOVED(was_counted)` and commit. If eligibility owner identity remains,
write `REVIEW_REMOVAL_NOTIFICATION_REQUESTED` in the same transaction. Notification
receives only review/product/recipient IDs; it never receives legal `reason`, case or
admin identity.

**PDP:** Product Detail BFF calls its existing internal read endpoint. Review returns
aggregate plus bounded approved preview, without eligibility, reports, legal state or
review revision. Database failure returns 503 so BFF can expose `UNAVAILABLE` rather
than fake an empty review section.
