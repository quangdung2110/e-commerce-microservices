---
doc_id: review-interfaces
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - contracts/events/review/v1/asyncapi.yaml
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
| `REVIEW_METRICS_UPDATED` | Kafka outbound | [AsyncAPI v1](../../contracts/events/review/v1/asyncapi.yaml) |
| Review lifecycle events | Kafka outbound | Machine-readable spec pending |
| `ORDER_COMPLETED` / `USER_DELETED` | Kafka inbound | Provider contract pending for Review subset |

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
reply. Only first creation writes `ADMIN_REPLY_CREATED`.

`POST /api/v1/admin/reviews/{review_id}/takedown` needs `legal_case_id`, reason,
requester and a different approver. It records `review_takedowns`, removes the review
from public visibility and writes `REVIEW_REMOVED`. The legal reason is never copied
to a public response or notification payload.

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

All events use `partition_key = product_id`, at-least-once delivery and outbox + CDC.

| Event | Meaning | Rating aggregator |
|---|---|---|
| `REVIEW_CREATED` | New approved review | Apply positive delta |
| `REVIEW_UPDATED` | Rating or media presence changed | Apply old/new delta; content-only update is ignored |
| `REVIEW_HIDDEN` | Threshold hides review | Apply negative delta |
| `REVIEW_APPROVED` | Admin restores hidden review | Apply positive delta |
| `REVIEW_REMOVED` | Legal takedown | Apply negative delta only when `was_counted=true` |
| `REVIEW_AUTHOR_ANONYMIZED` | Display name changed | Ignore |
| `ADMIN_REPLY_CREATED` | First official reply | Ignore |
| `REVIEW_METRICS_UPDATED` | Full product aggregate snapshot | Ignore; prevents loop |

`REVIEW_UPDATED` must carry `old_rating`, `new_rating`, `old_has_media`,
`new_has_media` and `affects_aggregate`. Content-only updates set
`affects_aggregate=false` and do not cause aggregate mutation/revision bump.

Each internal consumer inserts `(consumer_name, event_id)` into
`review_event_receipts` in the same transaction as its effect. Duplicate event ID is
a no-op. The rating aggregator no longer intentionally accepts double counting.

`REVIEW_METRICS_UPDATED` is the only metrics event Search consumes. It carries full
aggregate fields and `review_revision`; Search applies it iff incoming revision is
strictly greater than its document revision.

## 5. Inbound events

### `ORDER_COMPLETED`

Review consumes `event_id`, `order_id`, `user_id`, `completed_at` and every item’s
`product_id`, `sku_id`, `variant_name` snapshot and quantity. `variant_name` is
nullable for historic orders but must be supplied by current producer when known.

Review first inserts an `order-sync` receipt. In the same transaction it upserts
eligibilities by `(order_id, product_id, sku_id)` without changing a `CLAIMED` row.
The event must not trigger a synchronous Order lookup.

### `USER_DELETED`

Review consumes `event_id`, `user_id`, `deleted_at`, `reason` and `hard_delete_after`.
The privacy consumer receipt and `privacy_erasure_requests` record are written before
anonymizing author display names. A later worker performs hard purge at the requested
time; it nulls direct customer IDs but preserves reviews and aggregate.

## 6. Compatibility

The service is not implemented and all contracts remain `proposed`; the replacement
export endpoints are deliberately still v1. Consumers must move from the removed
stateless endpoint before adoption is marked verified.

Adding nullable `variant_name` to `ORDER_COMPLETED.items[]` is backward compatible.
For outbound events, adding optional fields is compatible; changing `partition_key`,
event ID or `review_revision` meaning is breaking. Contract tests must cover duplicate
event no-op, export-session expiry, snapshot/replay overlap and revision guard.
