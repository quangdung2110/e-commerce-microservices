---
doc_id: review-interfaces
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
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
| Public read/write review API | Inbound HTTP | Human-readable design; OpenAPI pending |
| Admin moderation API | Inbound HTTP | Human-readable design; OpenAPI pending |
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

### Create and edit

`POST /api/v1/reviews` receives `eligibility_id`, rating, optional content and
verified `media_asset_ids` (not object keys). In one transaction it claims the
eligibility, inserts the review/media links and writes `REVIEW_CREATED` to outbox.
Replay returns `409 ALREADY_REVIEWED`.

`PATCH /api/v1/reviews/{review_id}` can change rating, content and media once.
It requires `status=APPROVED` and `edit_count=0`; successful commit sets
`edit_count=1`, swaps media links and writes `REVIEW_UPDATED`. Replacement assets
become `DELETE_PENDING` after they are detached.

### Upload asset

`POST /api/v1/reviews/media/upload-urls` creates an `ISSUED` media asset and returns
`asset_id`, opaque object key and upload URL. Client uploads directly to storage.
Before a create/edit can attach it, Review verifies existence/type/metadata and moves
it to `VERIFIED`. An asset belongs to one user and attaches to at most one review.

### Report and moderation

`POST /api/v1/reviews/{review_id}/reports` creates one active report per reporter.
The neutral `202` response never exposes active report count or hidden state. At
threshold, Review writes `REVIEW_HIDDEN`; admin dismiss moves review back to
`APPROVED`, dismisses the reports and writes `REVIEW_APPROVED`.

`POST /api/v1/admin/reviews/{review_id}/replies` creates or edits the one official
reply. Only first creation writes `REVIEW_REPLY_NOTIFICATION_REQUESTED` when the
author identity is still available; reply edits never create a notification event.

`POST /api/v1/admin/reviews/{review_id}/takedown` needs `legal_case_id`, reason,
requester and a different approver. It records `review_takedowns`, removes the review
from public visibility and writes `REVIEW_REMOVED`. In the same transaction it writes
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
at-least-once delivery, outbox + CDC and Kafka `partition_key = product_id`. `id` is
the outbox UUID; `source` is `/services/review`; review facts use subject
`reviews/{review_id}`. A consumer must dispatch by exact `type` and ACK a known event
outside its responsibility without durable effect.

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
acknowledged by the rating consumer without applying a delta.

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
`variant_name` snapshot and quantity. `variant_name` is nullable for historic orders
but must be supplied by current producer when known.

Review first inserts an `order-sync` receipt using `CloudEvents.id`. In the same
transaction it upserts eligibilities by `(order_id, product_id, sku_id)` without
changing a `CLAIMED` row. The event must not trigger a synchronous Order lookup.

### `USER_DELETED`

Review consumes [Auth's `USER_DELETED` CloudEvents contract](../../contracts/events/auth/v1/asyncapi.yaml):
`id`, `user_id`, `deleted_at`, `reason` and `hard_delete_after`. The privacy consumer
receipt and `privacy_erasure_requests` record are written before anonymizing author
display names. A later worker performs hard purge at the requested time; it nulls
direct customer IDs but preserves reviews and aggregate.

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
