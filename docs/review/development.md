---
doc_id: review-development
service: review
scope: current
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

# Review Service — Development Guide

Review has no implementation/runtime yet. PostgreSQL 14+ with `wal_level=logical`,
Kafka/Connect and S3-compatible storage are the fixed dependencies; migration/runtime
tooling remains undecided.

## 1. Local schema

```bash
docker compose up -d postgres kafka kafka-connect minio
docker compose exec -T postgres psql -U postgres -d ecommerce < docs/review/schema/0001_initial_review_schema.sql
docker compose exec postgres psql -U postgres -d ecommerce -c "\dt review.*"
```

Schema CI must apply `0001` to an empty PostgreSQL 14 database. It must also verify
the dynamic outbox bootstrap created the current and next UTC partitions.

## 2. Implementation requirements

- Use a real PostgreSQL integration environment; in-memory databases do not exercise
  composite FK, partial indexes, enum, partition or trigger behavior.
- Generate public/admin and internal HTTP model code from
  `contracts/http/review/v1/openapi.yaml` and
  `contracts/http/review/internal/v1/openapi.yaml`.
- Generate/validate lifecycle, inbound and notification event models from the Review,
  Order and Auth AsyncAPI contracts.
- All mutable tables rely on database `updated_at` trigger; application must not
  replace it with ad-hoc timestamps.
- Object key is opaque; API receives `media_asset_id`, not arbitrary object key/URL.
- `REVIEW_AUTHOR_ALIAS_HMAC_SECRET` is required, loaded from the secret manager and
  stable in v1. Rotating it deliberately starts a new pseudonym namespace for future
  reviews; do not rotate it incidentally during ordinary deploys.
- `REVIEW_PRIVACY_GUARD_HMAC_SECRET` is required and stable while its privacy guards
  exist. It derives only the internal deletion match key, never a display alias. A key
  rotation needs key-version compatibility until every guard under the old key is gone.

## 3. Mandatory integration tests

| Area | Required proof |
|---|---|
| Entitlement | Duplicate order event is receipt no-op; one SKU/order creates one entitlement; create race has one winner; a late `ORDER_COMPLETED` persists `EXPIRED` with `expires_at` derived from `completed_at`, even when it predates `created_at`; a guarded subject's late order commits receipt but creates no eligibility |
| Review | Product/SKU composite FK rejects mismatch; only one edit succeeds; content hard cap rejects invalid content; same caller gets deterministic HMAC pseudonym and no profile/email is accepted; a guarded caller cannot create or edit |
| Media | User can issue bounded upload URLs; create/edit verifies immutable object metadata without a client verify/poll call; foreign, missing, invalid or reused asset is rejected; replacement and takedown queue `DELETE_PENDING` with their distinct reasons; `ISSUED` and `VERIFIED` assets past `expires_at` queue `UNATTACHED_EXPIRED`; a never-uploaded object reaches `DELETED` when Storage returns not-found; attach/expiry race has exactly one winner and an `ATTACHED` asset is never swept; a guarded caller cannot issue or attach an asset, while a normal caller still attaches only an asset they own |
| Moderation | Concurrent threshold reports produce one hide; dismiss resets active counter without resurrecting REMOVED; ACTIONED cannot have admin resolver; a guarded caller cannot create a report; a review hidden by threshold leaves the PENDING report queue and appears in the admin moderation list in the same transaction; dismissing by the review_id taken from that list restores it to APPROVED, closing the HIDDEN_BY_REPORT -> APPROVED edge end to end; `hidden_at` survives the restore; the list returns no reporter_user_id; `EXPLAIN` shows the list using `ix_review_hidden_queue` and never a sequential scan |
| Admin media | The moderation list returns signed media URLs for a HIDDEN_BY_REPORT review, with a TTL shorter than the public one; public list and owner read still return no URL for that same media; a report about an image is therefore adjudicable only through the admin surface |
| Takedown | One record per review; authenticated acting admin is persisted as `taken_down_by_admin_id`; legal reason and actor never appear public or in either Review event |
| Aggregate | Duplicate event changes no bucket twice; create/edit/hide/restore/remove yields exact bucket values and revision |
| Notification | First reply creates exactly one reply request; takedown creates removal request only for a non-purged owner; payload has no content, legal data or admin identity |
| Export | POST persists session offsets; every GET page echoes them; missing=404, expired=410; snapshot/replay overlap converges |
| Privacy | Event replay no-op; logical duplicate with a new event ID keeps one HMAC subject guard; anonymize changes pseudonym to `Khách hàng ẩn danh` without changing aggregate; concurrent Order/customer writes serialize with privacy through one subject lock; hard-purge nulls direct customer IDs and request subject ID but keeps the HMAC guard; test Auth-redriven missed deletion event because current direct publish can be lost after at most three retries |
| Outbox | Every command writes correct partition key; current/next partition exists; old-row count is never used as publish backlog; stored `payload` validates against the JSON Schema of its own `type` for all eight message types; an envelope missing `subject`/`dataschema`/`time` or disagreeing with its projection columns fails the domain transaction; a payload above 16 KiB is rejected at write time |
| Outbox CDC | Every row lands on `t.review.events` and no other topic, including a `REVIEW_METRIC` row; Kafka key equals `data.product_id`; the consumed message value is byte-equal to the stored `payload`; `table.expand.json.payload` round-trips all eight `data` shapes without dropping nullable fields |

`EXPLAIN` tests must cover main keyset query and SKU/rating/media filters. Test data
must be synthetic; review text and user identity are PII.

## 4. Migration and deployment rules

This initial schema is being edited directly because the service has no deployed
database. Once any environment contains data, changes require forward-only migrations:
add nullable/defaulted columns first, create large indexes concurrently, run dual-read/
write compatibility windows, then remove obsolete fields later.

Schema changes to outbox need Debezium coordination. Schema changes to export endpoints
need Search consumer contract test. Receipt cleanup must never run before the maximum
Kafka replay horizon.

## 5. Safe diagnostics

Read aggregate drift:

```sql
SELECT a.product_id, a.total_reviews, count(r.id) AS actual
FROM review.product_rating_aggregates a
LEFT JOIN review.reviews r
  ON r.product_id = a.product_id AND r.status = 'APPROVED'
GROUP BY a.product_id, a.total_reviews
HAVING a.total_reviews <> count(r.id);
```

Read active export sessions and their offsets; inspect `privacy_erasure_requests`
past deadline; inspect `review_media_assets` in `DELETE_PENDING`. Never delete a
replication slot, manually lower `review_revision`, or mark an asset `DELETED` before
storage confirms deletion.
