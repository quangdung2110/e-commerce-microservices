---
doc_id: review-interfaces
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
  - contracts/events/order/v1/asyncapi.yaml
  - contracts/events/auth/v1/asyncapi.yaml
verified_against_commit: null
---

# Review Service — Giao diện tích hợp

## 1. Contract catalogue

| Interaction | Direction | Contract status |
|---|---|---|
| Public read/write review API | Inbound HTTP | [OpenAPI v1](../../contracts/http/review/v1/openapi.yaml) |
| Admin moderation API | Inbound HTTP | [OpenAPI v1](../../contracts/http/review/v1/openapi.yaml) |
| PDP summary | Internal HTTP | [OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) |
| Rating export session/page | Internal HTTP | [OpenAPI v1](../../contracts/http/review/internal/v1/openapi.yaml) |
| Review lifecycle, metrics and notification requests | Kafka outbound | [Review AsyncAPI v1](../../contracts/events/review/v1/asyncapi.yaml) |
| `ORDER_COMPLETED` | Kafka inbound | [Order AsyncAPI v1 — Review subset](../../contracts/events/order/v1/asyncapi.yaml) |
| `USER_DELETED` | Kafka inbound | [Auth AsyncAPI v1](../../contracts/events/auth/v1/asyncapi.yaml) |

## 2. Public API semantics

Signed media URLs phải truy cập được từ trình duyệt. Presigner dùng
`REVIEW_S3_PUBLIC_ENDPOINT` (fallback `REVIEW_S3_ENDPOINT` khi trống); client nhận
URL hoàn chỉnh và không sửa host/path. S3 metadata/delete client vẫn dùng origin nội
bộ. Gateway root Compose giữ list/aggregate anonymous và xác thực JWT cho buyer/admin,
không inject danh tính dev cố định.


### Read

`GET /api/v1/reviews/products/{product_id}` lists only `APPROVED` reviews with
keyset cursor `(created_at, id)`. Optional filters are `sku_id`, `rating` and
`has_media=true`. Media URLs are generated at response time from verified attached
assets; persistent URLs are not stored.

`GET /api/v1/reviews/products/{product_id}/aggregate` returns the current async
aggregate or all-zero values if no aggregate row exists.

`GET /api/v1/reviews/me/pending` reads the caller's `PENDING` eligibility rows,
including `order_id`, the variant/product-name/product-image snapshots and `expires_at`.

`GET /api/v1/reviews/me` lists the caller's own reviews newest first (keyset
`(created_at, review_id)`), whatever their visibility: `HIDDEN_BY_REPORT` and `REMOVED`
reviews stay visible to their owner, with no signed media URL and no report, legal record
or takedown reason. Every item is the same `OwnerReview` the single read returns, so it
carries `product_name` and `product_image_url` ([ADR-012](./decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md)).

`GET /api/v1/reviews/me/eligibilities/{eligibility_id}` is the recovery read for a
create response lost after commit: it returns the caller-owned entitlement state and,
when `CLAIMED`, its review ID. `GET /api/v1/reviews/me/{review_id}` is the recovery
read for a lost edit response. Neither endpoint exposes reports, reporter identity,
legal record or takedown reason.

### Request bodies

Every request body schema is `additionalProperties: false`, and Review enforces it: a field
the contract does not define is `400 INVALID_REQUEST` and `errors[].field` names it. That is
what keeps a client from smuggling an actor, a status or a display name into a command
(`author_name` on create, `approved_by_admin_id` on takedown). `PATCH` always behaved this
way; the other bodies now do too.

### Create and edit

`POST /api/v1/reviews` receives `eligibility_id`, rating, optional content and
uploaded `media_asset_ids` (not object keys). Before its PostgreSQL transaction,
Review verifies the immutable storage metadata for each caller-owned asset. The
transaction then claims the eligibility, transitions valid assets to
`VERIFIED`/`ATTACHED`, inserts the review/media links and writes `REVIEW_CREATED` to
outbox. Review generates `author_display_name` as a fixed-format pseudonym from the
trusted `X-User-Id`; it accepts neither profile name nor email and does not call User
or consume profile events. Replay returns `409 ALREADY_REVIEWED`.

`PATCH /api/v1/reviews/{review_id}` can change rating, content and media once.
It requires `status=APPROVED` and `edit_count=0`; successful commit sets
`edit_count=1`, verifies newly supplied assets, swaps media links and writes
`REVIEW_UPDATED`. Replacement assets become `DELETE_PENDING` with
`deletion_reason=REPLACED` after they are detached.
The OpenAPI fixes partial-update semantics: an absent field is preserved,
`content: null` clears content, an absent `media_asset_ids` preserves attachments and
an explicit empty array removes them. A supplied media list is a complete replacement
of current attachments: it contains newly uploaded unattached asset IDs only; this v1
does not support reordering retained attachments.

### Upload asset

`POST /api/v1/reviews/media/upload-urls` creates an `ISSUED` media asset and returns
`asset_id`, a provider-neutral signed upload instruction and expiry. No response field
carries the object key. The signature necessarily addresses the object it authorizes, so
the key appears inside that URL; that is harmless because the key is unguessable random
material, holds no identifier, and the signature opens nothing else. A client never
chooses a key and never sends a key or an arbitrary URL back to Review.
Client uploads directly to storage, then simply sends `asset_id`
in create/edit; there is no client-facing verify/poll endpoint. Review verifies
existence/type/size/checksum when declared before attachment.

The issue request is checked before any key is generated. `content_type` must be one of
`image/jpeg`, `image/png`, `image/webp` for `IMAGE` or `video/mp4`, `video/webm` for `VIDEO`
(case-insensitive); `byte_size` must not exceed the limit for the type (10 MiB image, 100 MiB
video by default); anything else is `400 INVALID_REQUEST`. The signed upload pins that
content type and length, and the uploaded object must have exactly the declared size.
`checksum` is optional and is compared, case-insensitively, with the storage ETag of the
uploaded object: for the single-part `PUT` the instruction describes that is the hex MD5 of
the body. A different value makes the asset `MEDIA_NOT_VERIFIED` at attach time. A
`media_asset_ids` list that repeats an id is `400 INVALID_REQUEST`, as is one with more than
nine entries. An asset belongs to one
user, attaches to at most one review and is bounded by attachment and unattached-upload
quotas. In v1 the returned expiry is also the deadline to attach that asset: if an
`ISSUED` or `VERIFIED` asset reaches it un-attached, Review queues storage cleanup with
`deletion_reason=UNATTACHED_EXPIRED`; there is no client-visible media `EXPIRED` state.
The attach transaction and expiry sweep lock the same asset row, so exactly one wins:
an asset already queued for cleanup is rejected as unusable.

### Report and moderation

`POST /api/v1/reviews/{review_id}/reports` creates one active report per reporter.
The neutral `202` response never exposes active report count or hidden state. At
threshold, Review writes `REVIEW_HIDDEN`; admin dismiss moves review back to
`APPROVED` only when it was `HIDDEN_BY_REPORT`, dismisses the reports and writes
`REVIEW_APPROVED`. Dismiss never resurrects a terminal `REMOVED` review.

`PUT /api/v1/admin/reviews/{review_id}/reply` creates or replaces the one official
reply. Only first creation writes `REVIEW_REPLY_NOTIFICATION_REQUESTED` when the
eligibility owner has no HMAC deletion guard; replacement never creates a notification
event. A guard suppresses the request but does not reject the admin reply.
`GET /api/v1/admin/reviews?status=...` is the single admin moderation surface. Its
unit is the review, not the report, because
`POST /api/v1/admin/reviews/{review_id}/report-dismissals` acts on a review: three
reports on one review are one case, not three rows. `status=REPORTED` covers reviews
still public with undismissed reports, so a case can be settled before the threshold
fires. `status=HIDDEN_BY_REPORT` covers reviews already hidden. Reporter identity is
never returned on either.

The hidden state is what makes `REVIEW-BR-009` reachable at all. Hiding a review moves
every `PENDING` report on it to `ACTIONED`, so a queue keyed on report state loses the
case at the exact moment it starts needing adjudication, and the `review_id` that
dismissal requires becomes unobtainable. Both states share the keyset
`(first_reported_at, id)` ascending, so paging keeps one meaning across the hide
boundary and the longest-open case comes first. Each entry carries the review, its
media and its undismissed reports, so one read is enough to decide.

The surface carries short-lived signed media URLs, including for a review that is
`HIDDEN_BY_REPORT`. This is a deliberate exception to the rule that hidden media gets
no signed URL: a report about an image or video cannot be adjudicated without seeing
it. The exception is scoped to `/api/v1/admin/**`; public and owner reads still issue
no URL for non-`APPROVED` media, and the admin URL TTL must be shorter than the public
one.

`POST /api/v1/admin/reviews/{review_id}/takedown` needs `legal_case_id` and reason.
The single acting admin is read from trusted Gateway identity and persisted as
`taken_down_by_admin_id`; neither requester nor approver ID is accepted from the body.
It records `review_takedowns`, removes the review from public visibility and writes
`REVIEW_REMOVED` and queues each detached asset with `deletion_reason=TAKEDOWN`. In the
same transaction it writes
`REVIEW_REMOVAL_NOTIFICATION_REQUESTED` only when the eligibility owner has no HMAC
deletion guard. A guard does not block the legal takedown itself. The legal reason is
never copied to a public response or either event.

## 3. Internal API

Both internal surfaces are reachable only through the service mesh: an
`AuthorizationPolicy` allows the `product-detail-bff` principal on the PDP summary route
and the `search-service` principal on the export routes
([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
a wrong principal receives `403 text/plain` from the waypoint before reaching Review.
The static bearer token allowlists (`REVIEW_INTERNAL_PDP_TOKENS`,
`REVIEW_INTERNAL_EXPORT_TOKENS`) are defense-in-depth, not the authorization boundary. A
request with no `Authorization: Bearer` header is `401 UNAUTHENTICATED`; a token that is not on
the allowlist of the route's group is `403 FORBIDDEN`, so the PDP token cannot page the export
and the export token cannot read the PDP summary. An internal route that belongs to no group
rejects every caller. The filter matches the decoded path without `;` parameters (the path
Spring MVC routes on), not the raw URI, so `/%69nternal/v1/...` or `/internal/v1;x=1/...` cannot
skip the token check (`InternalIdentityFilterPathTest`).

### PDP summary

`GET /internal/v1/reviews/products/{product_id}/pdp-summary` remains a read-only BFF
endpoint. It returns aggregate and a bounded preview of `APPROVED` reviews. A product
with no review returns aggregate zero; PostgreSQL failure returns 503, never fake zero.
BFF propagates `traceparent` and a normalized `X-Request-Id` (8–128 characters, never a raw
client value), sends the bearer token from its `PDP_REVIEW_SERVICE_TOKEN` and
`preview_limit=3` by default, does not retry in the hot path and maps timeout/5xx to
`review_section.status = UNAVAILABLE`
([Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md),
[ADR-007](../product-detail-bff/decisions/ADR-007-hot-path-clarifications.md)). Token
rotation order: add the new token to `REVIEW_INTERNAL_PDP_TOKENS`, roll BFF, then remove
the old token.

### Rating aggregate export

The old stateless endpoint `GET /internal/v1/reviews/rating-aggregates` is replaced
inside v1 by two explicit endpoints:

```text
POST /internal/v1/reviews/rating-aggregate-exports
GET  /internal/v1/reviews/rating-aggregate-exports/{session_id}
```

`POST` is required because creating an export session writes durable state. Review
captures the current **end offset** of every `t.review.events` partition, persists
the session and its offsets, then returns `201` with `session_id`, `expires_at` and
`replay_from_offsets`.

`GET` returns keyset pages by `after_product_id`. Each page echoes the same session
ID and same offsets. Search imports all pages, then seeks to every returned offset
**inclusively** and replays only `REVIEW_METRICS_UPDATED`. Missing session is 404;
expired session is 410 and requires a fresh complete run.

Mutation can occur while page scan is in progress. This is safe: a mutation visible
in snapshot and replay is deduplicated by `review_revision`; a mutation not visible
in snapshot has an event at or after the captured end offset and is replayed.

## 4. Outbound events

Every event is a CloudEvents 1.0 structured JSON message on `t.review.events`, with
at-least-once delivery, outbox + CDC and Kafka `partition_key = product_id`. A consumer
must dispatch by exact `type` and ACK a known event outside its responsibility without
durable effect.

The complete envelope is written into `review_outbox.payload` inside the domain
transaction and reaches the wire verbatim; Debezium only selects topic, key and
headers, and never builds the envelope ([ADR-003](./decisions/ADR-003-outbox-cdc-publication.md)).
Every attribute required by `contracts/shared/json-schema/v1/cloud-event.schema.json`
therefore comes from the payload, not from an outbox column:

| Attribute | Value |
|---|---|
| `specversion` | Constant `1.0` |
| `id` | Outbox `event_id`; a fresh UUID per event, never reused |
| `type` | The `com.ecommerce.review.*.v1` value in the table below |
| `source` | Constant `/services/review` |
| `subject` | `reviews/{review_id}`, except `REVIEW_METRICS_UPDATED` which uses `product-rating-aggregates/{product_id}` |
| `time` | The transaction `now()`, equal to outbox `created_at` |
| `datacontenttype` | Constant `application/json` |
| `dataschema` | The JSON Schema path of that exact `type` |
| `sequence` | Present only on `REVIEW_METRICS_UPDATED`, equal to `data.review_revision` |
| `correlationid` | Present only when the originating request carried one |

Structured mode is normative: consumers read the envelope from the message body. The
`ce_type`, `ce_aggregate_type` and `ce_aggregate_id` Kafka headers are a convenience
mirror for cheap filtering and must never be read in place of the envelope.

| Event | CloudEvents type | Consumer | Meaning / payload boundary |
|---|---|---|---|
| `REVIEW_CREATED` | `com.ecommerce.review.created.v1` | Review rating aggregator | New approved review; `review_id`, product/SKU, rating and media presence. |
| `REVIEW_UPDATED` | `com.ecommerce.review.updated.v1` | Review rating aggregator | One permitted edit; carries old/new rating and media presence plus `affects_aggregate`. |
| `REVIEW_HIDDEN` | `com.ecommerce.review.hidden.v1` | Review rating aggregator | Report threshold hid an approved review; carries the negative-delta values. |
| `REVIEW_APPROVED` | `com.ecommerce.review.approved.v1` | Review rating aggregator | Admin restored a report-hidden review; this is never emitted on create. |
| `REVIEW_REMOVED` | `com.ecommerce.review.removed.v1` | Review rating aggregator | Legal takedown fact; `was_counted` decides whether to subtract. No legal case, reason or admin identity. |
| `REVIEW_METRICS_UPDATED` | `com.ecommerce.review.metrics-updated.v1` | Search | Complete Search-facing aggregate snapshot; `sequence = review_revision`. |
| `REVIEW_REPLY_NOTIFICATION_REQUESTED` | `com.ecommerce.review.reply-notification-requested.v1` | Notification | First official reply only when the eligibility owner has no deletion guard; review/product/recipient IDs, never reply content or admin identity. |
| `REVIEW_REMOVAL_NOTIFICATION_REQUESTED` | `com.ecommerce.review.removal-notification-requested.v1` | Notification | Safe takedown notice only when the eligibility owner has no deletion guard; review/product/recipient IDs, never legal case, reason or admin identity. |

`REVIEW_UPDATED` must carry `old_rating`, `new_rating`, `old_has_media`,
`new_has_media` and `affects_aggregate`. A content-only edit sets
`affects_aggregate=false`, creates no aggregate mutation or revision bump, and is
acknowledged by the rating consumer without applying a delta. A media replacement that
keeps both `old_has_media` and `new_has_media` true is likewise non-aggregate-affecting.

The rating aggregator inserts `(review-rating-aggregator, CloudEvents.id)` into
`review_event_receipts` in the same transaction as its effect. A duplicate event ID
is a no-op. It only consumes the first five event types in the table;
`REVIEW_METRICS_UPDATED` is ignored to prevent a loop. Search consumes only
`REVIEW_METRICS_UPDATED` and applies it iff incoming `review_revision` is strictly
greater than its document revision.

There is deliberately no `ADMIN_REPLY_CREATED` or `REVIEW_AUTHOR_ANONYMIZED` event.
The first has no consumer other than the targeted notification request; the latter is
unnecessary because every PII-owning service receives `USER_DELETED` directly from
Auth.

## 5. Inbound events

### `ORDER_COMPLETED`

Review consumes [Order's CloudEvents contract](../../contracts/events/order/v1/asyncapi.yaml):
`id`, canonical decimal-string `order_id`, `user_id`, `completed_at` and every item’s
`product_id`, `sku_id`, `variant_name`, `product_name` and `product_image_url` snapshots
and quantity. Review stores
`order_id` as text and compares it for equality; it does not cast the wire value to a
JSON number. Product and SKU identifiers remain opaque strings. `variant_name` is
nullable for historic orders
but must be supplied by current producer when known; `product_name` and
`product_image_url` are likewise nullable snapshots (absent for historic orders and for
producers older than ADR-012). `completed_at` is the business
timestamp stored on the eligibility and is the sole basis for its review window; it is
not replaced by Review's event-ingestion time.

Review derives the buyer's HMAC privacy subject key and takes its transaction-scoped
subject lock before it inserts an `order-sync` receipt using `CloudEvents.id`. If a
privacy request with that key exists in any state, Review commits the receipt, emits no
outbound event and suppresses the entitlement. Otherwise it inserts eligibilities by
`(order_id, product_id, sku_id)` with `ON CONFLICT DO NOTHING`; no existing
business-key row is changed. A late but valid unguarded event therefore records
`EXPIRED` with its original business expiry. The event must not trigger a synchronous
Order lookup.

### `USER_DELETED`

Review consumes [Auth's `USER_DELETED` CloudEvents contract](../../contracts/events/auth/v1/asyncapi.yaml):
`id` (UUID), `user_id`, `deleted_at`, `reason` and `hard_delete_after`. The privacy
consumer persists that UUID unchanged in both the `(privacy-sync, event_id)` receipt
and `privacy_erasure_requests.source_event_id`, then derives and stores a stable HMAC
subject key with the receipt and
`privacy_erasure_requests` row before anonymizing author display names. Admin
reply/takedown uses that guard, under the same subject lock, to suppress a new
notification request while preserving the command itself. A later worker performs hard
purge at the requested time; it nulls direct customer IDs but retains the HMAC guard,
reviews and aggregate. The listener never drops this event for a transient fault: it retries
without a time limit (backoff capped at one minute, every failed delivery counted by
`review_privacy_consumer_failure_total`), and only a record that violates its contract is
dead-lettered to `t.auth.events.dlq` ([ADR-013](./decisions/ADR-013-privacy-failure-handling.md)). Auth keeps its current best-effort direct publish:
after at most three retry attempts `USER_DELETED` can be missing. Review has no synchronous
Auth lookup or Auth outbox; Auth operational reconciliation/redrive must supply a
missed event before Review can start this lifecycle.

## 6. Compatibility

The three contracts are `implemented` ([ADOPTION](../../contracts/ADOPTION.md)): the provider
has tests that hold it to them (`HttpContractIT` for both OpenAPI documents,
`ReviewEventFactoryTest` for the events). They are not `verified`, which also needs consumer
contract tests. The replacement export endpoints are deliberately still v1; Search already
calls the session endpoints, and any other consumer must have moved off the removed stateless
endpoint before adoption is marked verified.

New producers must populate nullable `ORDER_COMPLETED.items[].variant_name`,
`product_name` and `product_image_url` whenever the source snapshot exists. For outbound events, adding an optional field is
compatible; changing a message type, `partition_key`, CloudEvents `id`, or the meaning
of `review_revision` is breaking. Contract tests must cover duplicate event no-op,
content-only update, notification payload redaction, export-session expiry,
snapshot/replay overlap and revision guard.

## 7. Error catalog

Every failure is `application/problem+json` with `type`, `title`, `status`, `detail`,
`instance`, `code`, `retryable` and, when there is something to say about a field, `errors[]`
(`field` in the wire's snake_case name, `message`). `detail` and `errors[].message` are written
by Review; framework exception text, class names and stack traces never reach a client
(`contracts/conventions/http.md`). `trace_id` echoes `X-Request-Id` only when it is 16–64
characters, the bounds of the shared Problem schema. A retryable error carries
`Retry-After: 5`.

| `code` | HTTP | Retryable | When |
|---|---:|:---:|---|
| `INVALID_REQUEST` | 400 | no | A parameter outside its range or of the wrong type, a missing required parameter, an invalid cursor, an unreadable body, a field the contract does not define, or a value the service refuses (content type, size, repeated asset id) |
| `UNAUTHENTICATED` | 401 | no | No trusted `X-User-Id`, or an internal route without a bearer token |
| `FORBIDDEN` | 403 | no | A non-admin on an admin route; a service token not allowed on the route group; a caller with a deletion guard (deliberately generic) |
| `NOT_FOUND` | 404 | no | A request for which the service has no operation |
| `METHOD_NOT_ALLOWED` | 405 | no | A known path with a method it does not accept; `Allow` lists the ones it does |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | no | A request body that is not `application/json` |
| `ELIGIBILITY_NOT_FOUND` | 404 | no | No such entitlement |
| `NOT_ELIGIBILITY_OWNER` | 403 | no | The entitlement belongs to another caller or its owner identity was purged |
| `ELIGIBILITY_EXPIRED` | 410 | no | A `PENDING` entitlement past `expires_at` |
| `ALREADY_REVIEWED` | 409 | no | The entitlement is already `CLAIMED` |
| `REVIEW_NOT_FOUND` | 404 | no | No such review, or not visible to this endpoint |
| `NOT_REVIEW_OWNER` | 403 | no | The review belongs to another caller |
| `EDIT_QUOTA_EXHAUSTED` | 409 | no | `edit_count` is already 1 |
| `REVIEW_NOT_EDITABLE` | 409 | no | The review is hidden or removed |
| `MEDIA_NOT_VERIFIED` | 409 | no | A caller-owned asset that is not usable: object absent, metadata mismatch, expired, already attached or queued for cleanup |
| `INVALID_MEDIA_REFERENCE` | 409 | no | `asset_id` does not resolve to an asset of the caller (unknown and foreign are the same answer) |
| `MEDIA_QUOTA_EXCEEDED` | 429 | yes | The unattached-asset or unattached-byte quota is exhausted; it frees as assets are attached or expire, so `Retry-After` is advisory |
| `ALREADY_REPORTED` | 409 | no | The caller already has an active report on the review |
| `REVIEW_REPORT_FORBIDDEN` | 403 | no | The caller is the author |
| `REVIEW_NOT_REPORTABLE` | 409 | no | Reserved; not produced in v1 (a non-`APPROVED` review answers `REVIEW_NOT_FOUND`) |
| `REVIEW_NOT_REPLYABLE` | 409 | no | The review is not `APPROVED` |
| `TAKEDOWN_ALREADY_EXISTS` | 409 | no | The review already has a legal takedown |
| `EXPORT_SESSION_NOT_FOUND` | 404 | no | No such export session |
| `EXPORT_SESSION_EXPIRED` | 410 | no | The session expired; Search starts a new run |
| `EXPORT_SESSION_UNAVAILABLE` | 503 | yes | Kafka end offsets could not be captured, or the session could not be stored |
| `REVIEW_STORE_UNAVAILABLE` | 503 | yes | PostgreSQL unreachable, a query timeout or a lock that could not be taken; never answered as an empty page or a zero aggregate |
| `MEDIA_STORAGE_UNAVAILABLE` | 503 | yes | The storage signer or metadata read failed; nothing unchecked is ever attached |
| `INTERNAL_ERROR` | 500 | no | A failure that is the service's own |

An `Accept` header that excludes both `application/json` and `application/problem+json` is
answered `406` with no body. `NOT_FOUND`, `METHOD_NOT_ALLOWED` and `UNSUPPORTED_MEDIA_TYPE`
apply to every path, so the OpenAPI documents do not repeat them per operation.
