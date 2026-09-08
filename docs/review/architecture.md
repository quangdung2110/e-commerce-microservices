---
doc_id: review-architecture
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
verified_against_commit: null
---

# Review Service — Architecture

## 1. Context and boundaries

Review owns entitlement, review content, media metadata, moderation, privacy erasure,
rating projection and the outbox. It reacts to `ORDER_COMPLETED` and `USER_DELETED`;
it never calls Order, Product, Auth or Search synchronously in a write transaction.

Product Detail BFF only calls internal PDP summary. Search only calls the internal
rating-export APIs and consumes metrics events. Neither may read Review PostgreSQL.

## 2. Components

```mermaid
flowchart TB
  GW[API Gateway/Admin] --> API[Public and admin API]
  BFF[Product Detail BFF] --> PDP[Internal PDP read]
  SEARCH[Search] --> EXP[Export-session API]
  ORDER[t.order.events] --> OC[Order consumer]
  AUTH[t.auth.events] --> PC[Privacy consumer]

  API --> APP[Application services]
  PDP --> REPO[Repositories]
  EXP --> REPO
  OC --> REPO
  PC --> REPO
  APP --> REPO
  REPO --> PG[(PostgreSQL: domain + receipts + sessions + outbox)]
  PG --> DBZ[Debezium] --> EVENTS[t.review.events]
  EVENTS --> AGG[Idempotent rating aggregator] --> REPO
  SCHED[Scheduler/workers] --> REPO
  API -. presigned URL .-> OBJ[Object storage]
  SCHED -. delete confirmed assets .-> OBJ
```

| Component | Responsibility |
|---|---|
| API/application service | Validate request, authorize caller, keep domain mutation/counter/outbox in one PostgreSQL transaction |
| Order consumer | Store receipt then upsert entitlement from self-contained `ORDER_COMPLETED` snapshot |
| Privacy consumer | Store receipt/request, anonymize author immediately; hard-purge worker removes direct IDs at deadline |
| Rating aggregator | Store receipt, apply aggregate delta, increment revision and write metric outbox atomically |
| Export-session API | Capture Kafka end offsets, persist session/offsets, serve short keyset pages |
| Asset verifier/deleter | Verify object before attach; delete storage object after `DELETE_PENDING` commit |
| Scheduler | Expire entitlement/assets/sessions, purge privacy IDs, reconcile aggregate, maintain outbox partitions |

## 3. Transaction boundaries

Every domain command includes its domain change, dependent counter/state, receipt when
it is event-driven, and outbox rows in one transaction. No storage/Kafka/HTTP side
effect occurs before commit.

Media is deliberately two-phase: storage upload and verification happen before attach;
storage deletion happens after database detach. This avoids referring to expiring URLs
or rolling back an external object operation.

## 4. Consistency model

| Data | Consistency |
|---|---|
| Eligibility, review, asset state, report, reply, takedown, privacy request | Strong in PostgreSQL |
| Aggregate | Eventual, normally seconds; idempotent consumer removes duplicate-count drift |
| Search rating | Eventual; revision guard handles event retries and export overlap |
| Search export session | Fixed Kafka watermark for the session lifetime |
| Object bytes | External; verified before attachment and deletion-confirmed asynchronously |

Content-only edits and author anonymization do not alter aggregate or
`review_revision`. They may emit lifecycle events for Analytics/Notification, but the
rating aggregator ignores them.

## 5. Concurrency

- Create claims eligibility with one conditional `UPDATE`; unique review is the final
  safety net.
- Edit/report/reply/takedown lock the review row with `FOR UPDATE`.
- Attach asset locks its asset row and requires state `VERIFIED`; unique `asset_id`
  prevents double attachment.
- Rating aggregator inserts event receipt first in its transaction. Duplicate receipt
  means no aggregate update. Deltas remain atomic SQL operations.
- Search does not hold a PostgreSQL transaction through all pages. It uses persisted
  Kafka watermark plus `review_revision` to converge correctly.

## 6. Architecture constraints

- No direct Kafka producer in business code; all outbound events use outbox/CDC.
- No direct database access by another service.
- No cache in Review read path.
- No synchronous cross-service call in a write path.
- Outbox is not a database queue with per-row `published_at`; health is observed via
  Debezium/Kafka Connect/replication slot telemetry.
- Review data is public only when `status='APPROVED'`; all public query indexes encode
  that predicate.
- A privacy hard purge removes direct customer identifiers, not review rating/content
  or aggregate history.
