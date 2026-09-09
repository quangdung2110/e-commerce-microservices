---
doc_id: review-interfaces
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - contracts/http/review/v1/openapi.yaml
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/events/order/v1/asyncapi.yaml
  - contracts/events/auth/v1/asyncapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
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

### Read

`GET /api/v1/reviews/products/{product_id}` lists only `APPROVED` reviews with
keyset cursor `(created_at, id)`. Optional filters are `sku_id`, `rating` and
`has_media=true`. Media URLs are generated at response time from verified attached
assets; persistent URLs are not stored.

`GET /api/v1/reviews/products/{product_id}/aggregate` returns the current async
aggregate or all-zero values if no aggregate row exists.

`GET /api/v1/reviews/me/pending` reads the caller's `PENDING` eligibility rows,
including `variant_name_snapshot` and `expires_at`.

`GET /api/v1/reviews/me/eligibilities/{eligibility_id}` is the recovery read for a
create response lost after commit: it returns the caller-owned entitlement state and,
when `CLAIMED`, its review ID. `GET /api/v1/reviews/me/{review_id}` is the recovery
read for a lost edit response. Neither endpoint exposes reports, reporter identity,
legal record or takedown reason.

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
`asset_id`, a provider-neutral signed upload instruction and expiry. It never returns
the opaque object key. Client uploads directly to storage, then simply sends `asset_id`
in create/edit; there is no client-facing verify/poll endpoint. Review verifies
existence/type/size/checksum when declared before attachment. An asset belongs to one
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
author identity is still available; replacement never creates a notification event.
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
`REVIEW_REMOVAL_NOTIFICATION_REQUESTED` only when the author identity remains
available. The legal reason is never copied to a public response or either event.

## 3. Internal API

### PDP summary

`GET /internal/v1/reviews/products/{product_id}/pdp-summary` remains a read-only BFF
endpoint. It returns aggregate and a bounded preview of `APPROVED` reviews. A product
with no review returns aggregate zero; PostgreSQL failure returns 503, never fake zero.

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
| `REVIEW_REPLY_NOTIFICATION_REQUESTED` | `com.ecommerce.review.reply-notification-requested.v1` | Notification | First official reply only; review/product/recipient IDs, never reply content or admin identity. |
| `REVIEW_REMOVAL_NOTIFICATION_REQUESTED` | `com.ecommerce.review.removal-notification-requested.v1` | Notification | Safe takedown notice; review/product/recipient IDs, never legal case, reason or admin identity. |

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
`id`, `order_id`, `user_id`, `completed_at` and every item’s `product_id`, `sku_id`,
`variant_name` snapshot and quantity. `order_id`, `product_id` and `sku_id` are opaque
strings owned by other services: Review compares them for equality and never parses,
sorts or derives anything from them. `variant_name` is nullable for historic orders
but must be supplied by current producer when known. `completed_at` is the business
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
`privacy_erasure_requests` row before anonymizing author display names. A later worker
performs hard purge at the requested time; it nulls direct customer IDs but retains the
HMAC guard, reviews and aggregate. Auth keeps its current best-effort direct publish:
after at most three retry attempts `USER_DELETED` can be missing. Review has no synchronous
Auth lookup or Auth outbox; Auth operational reconciliation/redrive must supply a
missed event before Review can start this lifecycle.

## 6. Compatibility

The service is not implemented and all contracts remain `proposed`; the replacement
export endpoints are deliberately still v1. Consumers must move from the removed
stateless endpoint before adoption is marked verified.

New producers must populate nullable `ORDER_COMPLETED.items[].variant_name` whenever
the source snapshot exists. For outbound events, adding an optional field is
compatible; changing a message type, `partition_key`, CloudEvents `id`, or the meaning
of `review_revision` is breaking. Contract tests must cover duplicate event no-op,
content-only update, notification payload redaction, export-session expiry,
snapshot/replay overlap and revision guard.
