---
doc_id: review-workflows
service: review
scope: current
status: implemented
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - docs/review/schema/0002_eligibility_product_snapshot.sql
  - docs/review/schema/0003_privacy_sweep_includes_failed.sql
  - docs/review/schema/0004_review_content_edited_at.sql
  - contracts/http/review/v1/openapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
  - contracts/events/review/v1/asyncapi.yaml
  - java/services/review-service/src/main/java/com/ecommerce/review
  - java/services/review-service/src/test/java/com/ecommerce/review
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
   item’s product/SKU; consume the `variant_name`, `product_name` and `product_image_url`
   snapshots when supplied.
2. Derive the buyer's HMAC privacy subject key. Begin transaction, take that
   transaction-scoped subject lock, then insert receipt `(order-sync, id)`. Existing
   receipt means no-op and ACK.
3. If a privacy request for the subject key exists in any state, commit the receipt,
   increment the bounded suppressed-event metric and ACK without creating an
   eligibility. Otherwise deduplicate `items` by `(product_id, sku_id)` keeping the
   first occurrence in array order, so a producer that repeats a pair cannot make the
   snapshotted `variant_name` depend on statement ordering. For every remaining item
   calculate `expires_at = completed_at + review_window_days` from
   the event's business timestamp. Insert one eligibility per
   `(order_id, product_id, sku_id)` with `ON CONFLICT DO NOTHING`: an existing
   business-key row is never overwritten. Set it `PENDING` when `expires_at > now()`;
   otherwise set it `EXPIRED`.
4. Commit then ACK.

Missing product/SKU is contract failure and goes to the DLQ at once, without retries, because no
retry can repair it. Missing variant name is accepted
as null for old orders, but new producers must send it; the same holds for the product name
and image snapshots (ADR-012). `created_at` records ingestion
only; it never extends the review window. `now()` is the transaction timestamp. No
event leaves Review.

## WF-REVIEW-002 — Media asset lifecycle

**Issue.** `POST /media/upload-urls` creates an `ISSUED` asset owned by caller, with
opaque key and TTL, and returns `asset_id` plus a provider-neutral signed upload
instruction. No response field carries the object key; it appears only inside the signed
URL that authorizes writing exactly that object, which is unguessable and
identifier-free. Issuing is bounded by
per-review, per-user-unattached and byte quotas. In its mutation transaction Review
takes the caller's privacy subject lock and rejects an existing deletion guard before
it writes `owner_user_id`. For a caller without a guard, ownership is unchanged:
`owner_user_id = caller`.

**Verify and attach.** Client has no verify/poll API. When it sends asset IDs in
create/edit, Review reads each immutable object metadata before its database
transaction and verifies existence, MIME/type, size and optional checksum. The
transaction first takes the caller's subject lock and rejects a deletion guard, then
locks the asset, transitions it through `VERIFIED` to `ATTACHED` and adds the media
link. An unknown or foreign `asset_id` is rejected as `INVALID_MEDIA_REFERENCE`, and a
caller-owned asset that is not usable — object absent, metadata mismatched, expired or
already attached — as `MEDIA_NOT_VERIFIED`. Both are `409`. The split is by what the
client can do about it: the second is recoverable by re-uploading, the first never is.
Unknown and foreign share one code on purpose, so the pair cannot be used to probe
whether an `asset_id` exists. A signed upload must be single-write/no-overwrite to
prevent post-check byte replacement.

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
saves old rating/media presence, replaces requested assets, sets `edit_count=1` and
`content_edited_at` to the transaction time, and
writes `REVIEW_UPDATED` carrying old/new metric values and `affects_aggregate`.
Content-only edits and media replacement retaining `has_media=true` set
`affects_aggregate=false`.

`content_edited_at` is null at creation; it is immutable across reporting, moderation,
replies and privacy. Owner reads use it for author-edit history; `updated_at` remains
a general record timestamp. Legacy missing/ambiguous edit history remains null.

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

Read the eligibility owner to derive its HMAC subject key. When it is non-null, take
the same transaction-scoped subject lock as the privacy consumer before locking the
review/eligibility and re-check the deletion guard. Lock review and require `APPROVED`.
Insert/update one reply; preserve both `created_by_admin_id` and
`updated_by_admin_id`. First insert writes `REVIEW_REPLY_NOTIFICATION_REQUESTED` in
the same transaction **only when no deletion guard exists**; it carries only
review/product/recipient IDs. A guard (including `RECEIVED`, `ANONYMIZED`, `FAILED` or
`PURGED`) never rejects the admin reply, but it suppresses that outbox row even while
the raw owner ID remains for the hard-delete retention window. Later content update does
not notify again. Reply state has no denormalized boolean on review.

## WF-REVIEW-007 — Report, hide and dismiss

**Report (`POST /api/v1/reviews/{review_id}/reports`):** take the caller's privacy
subject lock and reject a deletion guard, then lock review, reject author/hidden/removed
review, insert report and increase `active_report_count`. When that count rises from
zero, set `first_reported_at=now()` to open a moderation cycle. If threshold is reached,
set review hidden, set `hidden_at=now()`, transition all pending reports to `ACTIONED`,
and write one `REVIEW_HIDDEN` event.

**Work the queue (`GET /api/v1/admin/reviews?status=...`):** admin has exactly one
moderation surface and its unit is the review, because `report-dismissals` acts on a
review: three reports on one review are one case, not three rows.

`status=REPORTED` lists reviews still `APPROVED` with `active_report_count > 0`, read
through `ix_review_moderation_reported`. `status=HIDDEN_BY_REPORT` lists reviews the
threshold already hid, read through `ix_review_moderation_hidden`. The second is not
optional: hiding moves every `PENDING` report to `ACTIONED`, so a case keyed on report
state would vanish at the moment it starts needing a decision, and the `review_id` that
dismissal requires would be unobtainable — making `HIDDEN_BY_REPORT → APPROVED`
unreachable.

Both states share the keyset `(first_reported_at, id)` ascending, so paging means the
same thing before and after a review gets hidden and the longest-open case is handled
first. Each page then batch loads the review's media and its undismissed reports.
Reporter identity is never returned. Signed media URLs are issued even for a hidden
review; that exception exists only on the admin surface, because a report about an
image cannot be judged without seeing it.

**Dismiss (`POST /api/v1/admin/reviews/{review_id}/report-dismissals`):** admin locks
review, changes its `ACTIONED`/`PENDING` reports to
`DISMISSED` and recomputes active count. It restores only a `HIDDEN_BY_REPORT` review
to `APPROVED` and writes `REVIEW_APPROVED`; a terminal `REMOVED` review stays removed.
`hidden_at` and `first_reported_at` are deliberately left in place so a repeatedly
brigaded review keeps its history; every index over them is partial on status and
report count, so the stale values are inert. A later report reopens a cycle by
overwriting `first_reported_at`.
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

1. Validate the Auth CloudEvents `id` as UUID before the transaction; it is persisted
   unchanged in the UUID receipt and `source_event_id` columns. Derive the HMAC privacy
   subject key, begin a transaction and take its subject lock. Store
   `(privacy-sync, event_id)` receipt and insert `privacy_erasure_requests` with
   `ON CONFLICT (subject_match_key) DO NOTHING`. A logical duplicate deletion with a
   new event ID only commits its receipt and changes nothing else.
2. Find the subject's reviews by joining `reviews` to `review_eligibilities` through
   `ix_eligibility_owner_erasure`, then set `author_display_name='Khách hàng ẩn danh'`
   and `author_anonymized_at=now()`. Do not emit a Review event or touch
   aggregate/revision: each PII-owning downstream service gets `USER_DELETED` directly
   from Auth.
3. Set `status='ANONYMIZED'` **and** `anonymized_at=now()`; ACK only after commit. From
   that commit onward, Order input is suppressed and customer upload/create/edit/report
   writes for the subject are rejected by the same guard. Admin reply/takedown takes
   the same subject lock and still completes, but its notification-producing branch is
   suppressed when the guard exists.
4. At `hard_delete_after`, a worker takes the same subject lock and, in one transaction,
   writes every column in the table below. The worker is the daily pass (WF-REVIEW-011); it
   works through every due request, `FAILED` ones included. It retains the HMAC subject key to suppress
   late/replayed input without retaining a direct identifier.

Each erasable table pairs its identifier with an erasure timestamp under a CHECK that
requires both or neither, so clearing the identifier alone is rejected and the whole
purge rolls back. That failure is deterministic, not transient: the critical-severity
retry below would repeat it forever and the legal deadline would pass with the data
still present. Every purge UPDATE must therefore write both columns of its row at once.

| Table | Rows | Must set together |
|---|---|---|
| `review_eligibilities` | `owner_user_id = :subject` | `owner_user_id=NULL`, `owner_erased_at=now()` |
| `review_media_assets` | `owner_user_id = :subject` | `owner_user_id=NULL`, `owner_erased_at=now()` |
| `review_reports` | `reporter_user_id = :subject` | `reporter_user_id=NULL`, `reporter_erased_at=now()` |
| `privacy_erasure_requests` | the subject's request | `subject_user_id=NULL`, `purged_at=now()`, `status='PURGED'` |

`ck_privacy_status_timestamps_consistent` also requires `anonymized_at` to already be
set before `PURGED` is accepted, which step 3 guarantees. Each of the first three
lookups uses a partial index on `<identifier> IS NOT NULL`
(`ix_eligibility_owner_erasure`, `ix_media_asset_owner_erasure`,
`ix_report_reporter_erasure`), so a purged row leaves the index and neither the
anonymize nor the purge scans a whole table on a deadline-bound path.

Reviews, rating, content and opaque asset keys remain. Admin/legal identity retention
is not altered by customer-erasure worker.

A purge that fails in its transaction marks the request `FAILED`, logs it at `error` and
leaves every erasable row as it was. The request is due again on the next daily pass and is
purged then if the cause has cleared; until it reaches `PURGED` it stays counted by
`review_privacy_hard_delete_overdue_total`, so the critical alert holds whether the cause is
transient or a defect that a retry cannot fix. The guard is unaffected by `FAILED`: late or
replayed input is still suppressed. A `USER_DELETED` that cannot be applied is likewise never
dead-lettered for a transient fault. The listener retries it without a time limit, backoff
capped at one minute, and counts every failed delivery in
`review_privacy_consumer_failure_total`; only a record that violates its contract goes to
`t.auth.events.dlq`, at once ([ADR-013](./decisions/ADR-013-privacy-failure-handling.md)).

Auth deliberately retains its current
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
alert on privacy deadline; and create the outbox partitions of the current and next UTC
month. The asset sweep locks each candidate asset row and performs a conditional state
transition. It races safely with attach: attach that commits `ATTACHED` first removes
the row from the sweep; a sweep that commits `DELETE_PENDING` first makes later attach
fail as unusable. No storage operation happens in this scheduler transaction.

Daily: process every asset `DELETE_PENDING` regardless of reason, in pages until none is
left; delete its `object_key` and only then mark it `DELETED`. “Object not found” is
idempotent success; transient or definitive storage failures leave it `DELETE_PENDING` for
the next pass and alerting, and an asset that fails does not hold back the ones behind it.
Also run the privacy hard purge (every due request, `FAILED` included, in pages until none
is left), compare aggregate with approved reviews, and repair drift with a revision
increment plus metric outbox event. Aggregate replay duplicates should no longer cause
drift; reconciliation remains a corruption/bug safety net. It is not exact against events
still in flight: a review created moments before the run, whose event the aggregator has not
yet applied, is counted by the recount and then again by the late event, and the next run
corrects it ([ADR-005](./decisions/ADR-005-async-rating-aggregation.md)). The run is at
02:15 to keep that window small.

Review never drops outbox partitions. Dropping one needs proof from Kafka Connect and the
replication-slot telemetry that every change in its range was published, which Review cannot
observe, so it is an operator action ([operations.md](./operations.md#5-outbox-operational-rule)).
Review never counts old outbox rows as “unpublished backlog”.

## WF-REVIEW-012 — Takedown and PDP summary

**Takedown (`POST /api/v1/admin/reviews/{review_id}/takedown`):** read the eligibility
owner to derive its HMAC subject key. When it is non-null, take the same
transaction-scoped subject lock as privacy before locking review/eligibility and
re-check the deletion guard. Lock review, validate legal case/reason and the caller's
admin role, then insert the one audit record with `taken_down_by_admin_id` from trusted
Gateway identity. Set review `REMOVED`, detach assets to `DELETE_PENDING` with
`deletion_reason=TAKEDOWN`, and write `REVIEW_REMOVED(was_counted)`. If no guard
exists, write `REVIEW_REMOVAL_NOTIFICATION_REQUESTED` in the same transaction;
otherwise omit only that outbox row and still commit the takedown. Notification receives
only review/product/recipient IDs; it never receives legal `reason`, case or admin
identity.

**PDP:** Product Detail BFF calls its existing internal read endpoint. Review returns
aggregate plus bounded approved preview, without eligibility, reports, legal state or
review revision. Database failure returns 503 so BFF can expose `UNAVAILABLE` rather
than fake an empty review section.
