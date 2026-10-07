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

Review runs from [`java/services/review-service`](../../java/services/review-service):
Java 21 and Spring Boot 3.3, Spring JDBC over PostgreSQL 14+ with `wal_level=logical`,
Flyway for migrations, Spring Kafka for the three consumers, and the AWS S3 SDK against
any S3-compatible object storage (MinIO in compose). Kafka/Connect and object storage are
fixed dependencies of the design, not of the read path.

## 0. Runtime choices and why

**Spring JDBC, not an ORM.** Every command in this design is a specific statement: a
conditional `UPDATE` that doubles as the ownership check, a `FOR UPDATE` row lock, an
`ON CONFLICT DO NOTHING` receipt, an advisory lock, a bucket delta re-derived in SQL. The
schema also leans on PostgreSQL enums, partial indexes, a partitioned outbox and
`updated_at` triggers. An ORM would hide exactly the statements the workflows specify.

**The privacy subject lock is `pg_advisory_xact_lock`** on a `bigint` taken from the
first eight bytes of `subject_match_key`. It is transaction-scoped, so it releases on
commit or rollback, and it is per subject rather than global. A collision would make two
unrelated subjects share a lock briefly — a little contention, no correctness cost.

**Transaction boundaries are explicit where a step must stay outside them.** Create and
edit read object metadata from storage before opening their transaction, and the privacy
purge runs one transaction per subject, so those use a `TransactionTemplate` rather than
an annotation that self-invocation would silently bypass.

**`product_rating_aggregates.updated_at` is written by the statement, not a trigger.**
The schema deliberately puts `updated_at` triggers on the five mutable domain tables and
not on this projection, whose every write already recomputes the whole row.

## 1. Local schema

The service applies `V1__init_review_schema.sql` with Flyway at startup; that file is
kept byte-identical to
[`0001_initial_review_schema.sql`](./schema/0001_initial_review_schema.sql) and
`SchemaSyncTest` fails the build if the two ever drift.

Applying the schema by hand needs PostgreSQL only. `init-db.sh` creates the
`review_service` database, and the compose PostgreSQL already runs with
`wal_level=logical`, which the outbox publication requires.

```bash
docker compose up -d postgres
docker compose exec -T postgres psql -U postgres -d review_service < docs/review/schema/0001_initial_review_schema.sql
docker compose exec postgres psql -U postgres -d review_service -c "\dt review.*"
```

`init-db.sh` only runs when the PostgreSQL volume is first created. On an existing
volume the database will be missing; create it once rather than wiping the volume:

```bash
docker compose exec postgres psql -U postgres -c "CREATE DATABASE review_service"
```

Schema CI must apply `0001` to an empty PostgreSQL 14 database. It must also verify
the dynamic outbox bootstrap created the current and next UTC partitions.

### Auth → Review `USER_DELETED` persistence contract test

[`test-auth-user-deleted-to-review.ps1`](../../contracts/tools/test-auth-user-deleted-to-review.ps1)
uses the canonical Auth fixture and the real Review DDL. It first checks that Auth's
common envelope asserts UUID `id` even under a format-annotation-only validator, then
creates only the disposable database
`review_contract_auth_user_deleted`, applies `0001`, and proves the same Auth event ID
inserts one `(privacy-sync, event_id)` receipt and one
`privacy_erasure_requests.source_event_id`. The database is dropped in `finally`; it
never uses or clears `review_service`.

```powershell
pwsh ./contracts/tools/test-auth-user-deleted-to-review.ps1 -ValidateOnly
docker compose up -d postgres
pwsh ./contracts/tools/test-auth-user-deleted-to-review.ps1
```

This is a persistence-boundary contract test of the consumer transaction specified in
WF-REVIEW-010. The fixture is retained: `PrivacyIT` now runs the same assertions through
the application's own privacy consumer path against a real PostgreSQL, so the two
together cover the contract boundary and the runtime.

## 1a. Running the service locally

Storage có hai origin: `REVIEW_S3_ENDPOINT=http://minio:9000` cho server HEAD/DELETE,
`REVIEW_S3_PUBLIC_ENDPOINT=http://localhost:9000` cho signed upload/download tới trình
duyệt. Biến public để trống thì fallback endpoint nội bộ. Presigner ký đúng host ngay
lúc sinh URL; frontend không thay host/path vì làm hỏng SigV4. Production đặt origin
HTTPS mà browser truy cập được. Public/owner/admin read vẫn trả URL ngắn hạn per-response.

Gateway local tách GET list/aggregate anonymous, còn buyer/admin đi qua
`authenticated@file` và `gateway-auth`, dùng đúng access-token identity.


Compose runs `minio` for object bytes and `review-service` itself. The Debezium
connector is registered by hand once PostgreSQL has the schema; its configuration lives
in [ADR-003](./decisions/ADR-003-outbox-cdc-publication.md) and is checked in at
[`review-postgres-connector.json`](../../java/services/review-service/src/main/resources/debezium/review-postgres-connector.json).
Until it is registered the aggregate does not move: Review has no business Kafka
producer, so lifecycle facts reach the topic only through CDC.

```bash
docker compose up -d postgres kafka kafka-connect minio review-service
curl -X POST http://localhost:8084/connectors -H 'Content-Type: application/json' \
  -d @java/services/review-service/src/main/resources/debezium/review-postgres-connector.json
```

Build and test from the module directory. The integration tests start their own
PostgreSQL, Kafka and MinIO through Testcontainers, so they need Docker but not compose:

```bash
cd java/services/review-service && mvn verify
```

## 2. Implementation requirements

- Use a real PostgreSQL integration environment; in-memory databases do not exercise
  composite FK, partial indexes, enum, partition or trigger behavior.
- HTTP request and response models mirror
  `contracts/http/review/v1/openapi.yaml` and
  `contracts/http/review/internal/v1/openapi.yaml` field for field. Both are
  `additionalProperties: false`, so adding a field without a contract change is a
  breaking change.
- Event models mirror the Review, Order and Auth AsyncAPI contracts, and every stored
  outbox envelope is validated against the JSON Schema of its own `type` by
  `ReviewEventFactoryTest`, loaded from `contracts/` rather than copied.
- The five mutable domain tables rely on the database `updated_at` trigger; application
  code must not replace it with ad-hoc timestamps.
- Object key is opaque; API receives `media_asset_id`, not arbitrary object key/URL. The
  signed upload URL necessarily addresses the object it signs, but no response carries an
  `object_key` field, the key holds no user identifier, and a client can neither choose a
  key nor reach an object outside the one signature it was given.
- `REVIEW_AUTHOR_ALIAS_HMAC_SECRET` is required, loaded from the secret manager and
  stable in v1. Rotating it deliberately starts a new pseudonym namespace for future
  reviews; do not rotate it incidentally during ordinary deploys.
- `REVIEW_PRIVACY_GUARD_HMAC_SECRET` is required and stable while its privacy guards
  exist. It derives only the internal deletion match key, never a display alias. A key
  rotation needs key-version compatibility until every guard under the old key is gone.

## 3. Mandatory integration tests

| Area | Required proof |
|---|---|
| Entitlement | Order's producer contract accepts only a canonical positive decimal-string `order_id`; Review stores the accepted string without numeric coercion; duplicate order event is receipt no-op; one SKU/order creates one entitlement; an event repeating the same (product_id, sku_id) creates one eligibility carrying the first occurrence's variant_name, and an event above 200 items fails producer contract validation; create race has one winner; a late `ORDER_COMPLETED` persists `EXPIRED` with `expires_at` derived from `completed_at`, even when it predates `created_at`; a guarded subject's late order commits receipt but creates no eligibility; the product name/image snapshot is stored when supplied and stays null for historic orders; pending list and owner reads carry that snapshot; `GET /reviews/me` lists only the caller's reviews newest first, pages by keyset and keeps hidden/removed reviews without signed media |
| Review | Product/SKU composite FK rejects mismatch; only one edit succeeds; content hard cap rejects invalid content; same caller gets deterministic HMAC pseudonym and no profile/email is accepted; a guarded caller cannot create or edit |
| Media | User can issue bounded upload URLs; create/edit verifies immutable object metadata without a client verify/poll call; an unknown or foreign asset_id returns 409 INVALID_MEDIA_REFERENCE and a caller-owned asset that is absent, mismatched, expired or already attached returns 409 MEDIA_NOT_VERIFIED, with unknown and foreign indistinguishable to the caller; replacement and takedown queue `DELETE_PENDING` with their distinct reasons; `ISSUED` and `VERIFIED` assets past `expires_at` queue `UNATTACHED_EXPIRED`; a never-uploaded object reaches `DELETED` when Storage returns not-found; attach/expiry race has exactly one winner and an `ATTACHED` asset is never swept; a guarded caller cannot issue or attach an asset, while a normal caller still attaches only an asset they own |
| Moderation | Concurrent threshold reports produce one hide; dismiss resets active counter without resurrecting REMOVED; ACTIONED cannot have admin resolver; a guarded caller cannot create a report; a review with reports appears once under status=REPORTED no matter how many reports it has, and moves to status=HIDDEN_BY_REPORT on the same page position when the threshold fires; dismissing by the review_id taken from that list restores it to APPROVED, closing the HIDDEN_BY_REPORT -> APPROVED edge end to end; `first_reported_at` and `hidden_at` survive the restore and a new report reopens the cycle; the list returns no reporter_user_id; `EXPLAIN` shows both statuses using `ix_review_moderation_reported`/`ix_review_moderation_hidden` and never a sequential scan |
| Admin media | The moderation list returns signed media URLs for a HIDDEN_BY_REPORT review, with a TTL shorter than the public one; public list and owner read still return no URL for that same media; a report about an image is therefore adjudicable only through the admin surface |
| Takedown | One record per review; authenticated acting admin is persisted as `taken_down_by_admin_id`; legal reason and actor never appear public or in either Review event |
| Aggregate | Duplicate event changes no bucket twice; create/edit/hide/restore/remove yields exact bucket values and revision |
| Notification | First reply creates exactly one reply request and takedown creates one removal request only when the eligibility owner has no deletion guard. With an `ANONYMIZED` guard but a still-present raw `owner_user_id`, each admin command persists normally and writes zero notification outbox rows. A concurrent privacy/admin-command test proves the shared subject lock makes a guard committed first suppress the request. Payload has no content, legal data or admin identity. |
| Export | POST persists session offsets; every GET page echoes them; missing=404, expired=410; snapshot/replay overlap converges |
| Privacy | Auth common schema rejects non-UUID CloudEvents `id`; the canonical Auth `USER_DELETED` UUID inserts exactly one `(privacy-sync, event_id)` receipt and one `privacy_erasure_requests.source_event_id`; event replay no-op; logical duplicate with a new event ID keeps one HMAC subject guard; anonymize changes pseudonym to `Khách hàng ẩn danh` without changing aggregate; concurrent Order/customer writes and the notification-producing branch of admin reply/takedown serialize with privacy through one subject lock; hard-purge nulls direct customer IDs and request subject ID but keeps the HMAC guard; a purge that clears an identifier without writing its paired `*_erased_at` is rejected by the CHECK, proving the pairing is enforced rather than assumed; one end-to-end run reaches `PURGED` with `anonymized_at` and `purged_at` both set and every identifier column NULL across all four tables; `EXPLAIN` shows anonymize and purge using `ix_eligibility_owner_erasure`, `ix_media_asset_owner_erasure` and `ix_report_reporter_erasure`, never a sequential scan; test Auth-redriven missed deletion event because current direct publish can be lost after at most three retries |
| Outbox | Every command writes correct partition key; current/next partition exists; old-row count is never used as publish backlog; stored `payload` validates against the JSON Schema of its own `type` for all eight message types; an envelope missing `subject`/`dataschema`/`time` or disagreeing with its projection columns fails the domain transaction; a payload above 16 KiB is rejected at write time |
| Outbox CDC | Every row lands on `t.review.events` and no other topic, including a `REVIEW_METRIC` row; Kafka key equals `data.product_id`; the consumed message value is byte-equal to the stored `payload`; `table.expand.json.payload` round-trips all eight `data` shapes without dropping nullable fields |

`EXPLAIN` tests must cover main keyset query and SKU/rating/media filters. Test data
must be synthetic; review text and user identity are PII.

Every row above is covered by a test in
[`src/test/java/com/ecommerce/review/integration`](../../java/services/review-service/src/test/java/com/ecommerce/review/integration),
plus the schema-validation and pseudonym tests under Surefire. Two of them state where
the proof lives because it is not where the row suggests:

- The contract-level rejections of a JSON-number `order_id` and of an over-200-item
  `ORDER_COMPLETED` belong to the Order producer and its JSON Schema, not to Review. On
  the Review side the equivalent proof is that an opaque `order_id` of any shape
  round-trips unparsed, which `EntitlementIT` covers.
- "Old-row count is never used as publish backlog" is structural rather than testable:
  `review_outbox` has no `published_at` column and no code reads its age. It is enforced
  by the schema and by the runbook, not by an assertion.

## 4. Migration and deployment rules

`V1` is the initial schema and is applied by Flyway. It may still be edited in place
only while no environment holds Review data; the moment one does, changes require
forward-only migrations: add nullable/defaulted columns first, create large indexes
concurrently, run dual-read/write compatibility windows, then remove obsolete fields
later. Any edit to `V1` must be mirrored into
`docs/review/schema/0001_initial_review_schema.sql` in the same commit — `SchemaSyncTest`
compares the two byte for byte.

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
