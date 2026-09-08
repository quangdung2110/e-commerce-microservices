---
doc_id: review-overview
service: review
scope: target
status: proposed
owners:
  - Review Service team
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
verified_against_commit: null
---

# Review Service — Service Overview

Review Service is planned; no runtime implementation exists yet. These documents are
the target design and must be implemented together with their contracts.

## Purpose and boundary

Review grants a buyer one review entitlement per purchased SKU, maintains the public
review lifecycle, media metadata, moderation, legal takedowns and product rating
aggregates. It publishes lifecycle/metric events through outbox CDC.

Review does not own orders, catalog, user accounts, object bytes, Search documents or
notifications. It receives immutable purchase snapshot fields in `ORDER_COMPLETED`
and user-deletion instructions in `USER_DELETED`; it does not call those services in
the write path.

## Data ownership

| Store | Owned data |
|---|---|
| PostgreSQL `review` | entitlement, review, media asset metadata, moderation, privacy request, aggregate, consumer receipt, export session, outbox |
| Object storage | image/video bytes |
| Kafka | transport only; not source of truth |

`variant_name_snapshot` comes from Order at completion. `author_display_name` is a
write-time Auth/User snapshot. Both are not profile-synchronized. Account deletion
anonymizes display name immediately and removes direct customer IDs at the supplied
hard-delete deadline.

## Core capabilities

- Grant and expire verified-purchase review entitlements.
- Create one review and edit it once.
- Upload, verify, attach and asynchronously delete media assets.
- List only approved reviews with keyset pagination and expose rating aggregate.
- Report, auto-hide, dismiss reports, reply and legally take down reviews.
- Process all internal/inbound events idempotently with database receipts.
- Export rating aggregate through durable Search export sessions.
- Publish business events only through PostgreSQL outbox and Debezium.

## Critical invariants

- `REVIEW-INV-001`: one `(order_id, product_id, sku_id)` has at most one entitlement.
- `REVIEW-INV-002`: one entitlement creates at most one review.
- `REVIEW-INV-003`: review product/SKU always match its entitlement.
- `REVIEW-INV-004`: one review can be edited at most once.
- `REVIEW-INV-005`: aggregate total and average derive from star buckets.
- `REVIEW-INV-006`: every aggregate mutation increments `review_revision` and writes
  a metric outbox event in one transaction.
- `REVIEW-INV-007`: all outbound business events use outbox CDC.
- `REVIEW-INV-008`: event consumer effect is idempotent by receipt.
- `REVIEW-INV-009`: every page of an export session uses the same Kafka offsets.
- `REVIEW-INV-010`: direct customer identity is purged at `hard_delete_after`.

## Documents

| Document | Purpose |
|---|---|
| [Architecture](./architecture.md) | Components, transaction boundaries and consistency |
| [Domain](./domain.md) | Business rules, invariants and state machines |
| [Interfaces](./interfaces.md) | Public/internal semantics and event contracts |
| [Workflows](./workflows.md) | End-to-end behavior and recovery |
| [Data](./data.md) | Tables, relations, lifecycle and indexes |
| [Operations](./operations.md) | SLOs, alerts and runbooks |
| [Development](./development.md) | Local setup and verification |
| [Decisions](./decisions/README.md) | ADRs |

## Known gaps

- Runtime, migration tool, concrete object-storage provider and public OpenAPI are
  still unchosen.
- Lifecycle event and inbound event machine-readable contracts still need to be added.
- The privacy retention rule for admin/legal identities needs a compliance policy;
  customer erasure only covers direct customer identifiers.
- Event receipt retention must be configured to exceed Kafka retention plus replay
  safety margin before implementation is deployed.
