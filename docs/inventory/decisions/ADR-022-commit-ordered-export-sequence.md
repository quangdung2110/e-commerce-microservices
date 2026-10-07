# ADR-022 — Commit-ordered export sequence and revision-guarded snapshot

## Status

Accepted — 2026-10-03. Refines the export-sequence and snapshot rules of ADR-014 and
ADR-016. Their at-least-once delivery, readiness gate, per-SKU revision guard and
30-day replay window remain unchanged.

## Context

The change feed `GET /exports/inventory/v1/changes?after_sequence=N` and the
CloudEvents `sequence` promised a sequence "assigned at durable creation". With
concurrent transactions (command transactions, several availability-projector
workers), a database sequence value is taken before commit. A transaction holding
sequence 100 can commit after a transaction holding 101. A Search reader that has
already advanced its cursor to 101 would skip 100 forever: gaps were declared
acceptable, so the reader cannot tell a permanent gap from an uncommitted one.

The snapshot session also promised rows "at that fixed watermark". Serving historical
per-SKU state for a 24-hour session requires keeping state history, although Search
already applies data with a strict per-SKU `availability_revision` guard.

## Decision

### Commit-ordered sequencing

- A business or projector transaction writes its public fact to `OutboxEvent` with no
  export sequence. This keeps the atomic state/evidence/outbox commit of ADR-011.
- A single sequencer (one active instance, holding a database lease with fencing) runs
  short transactions that take committed unsequenced `OutboxEvent` rows in
  `outbox_id` order, assign the next values of a monotonic export sequence, and insert
  each finalized CloudEvent (with `sequence` set) into `PublishedEvent`, marking the
  source row sequenced in the same transaction.
- Because sequencer transactions are serialized and each sees only committed rows, a
  row that becomes visible later always receives a larger sequence. A reader that has
  observed sequence `N` never later observes a new row with sequence `<= N`. Gaps can
  still occur (sequence cache, rolled-back sequencer transaction) but are permanent and
  safe to skip.
- `PublishedEvent` is the only relay source: Debezium/Kafka Connect captures it for
  Kafka (ADR-023), and the change-feed endpoint reads it. A fact is never visible on
  either path before it has a sequence. The rule applies to both Inventory topics.
- Sequencing lag counts toward the outbox-age alerts and the 60-second Search freshness
  SLO. If the sequencer cannot run, facts accumulate durably and mutations continue
  while outbox capacity is safe (existing fail-closed retention rule).

### Revision-guarded snapshot

- `POST /snapshot-sessions` records `export_watermark` = the greatest assigned export
  sequence at creation.
- Snapshot pages return the **current** `SkuAvailabilityState` of SKUs with active
  readiness, each with its `availability_revision`. A row may therefore be newer than
  the watermark; it is never older than the state the watermark implies.
- Search applies snapshot rows and then the change feed with `sequence > watermark`,
  accepting a fact only when its revision is greater than the stored revision. Every
  change after the watermark is in the feed; a newer snapshot row only makes some feed
  facts no-ops. No state history table is required.

## Alternatives considered

- **Assign sequence in the writing transaction (database sequence/identity).** Simple
  but not commit-ordered; loses facts as described. Rejected.
- **Serialize all writers on one sequence row lock.** Commit-ordered, but makes every
  stock command contend on one row and violates the latency targets. Rejected.
- **Expose sequences only below the oldest in-flight transaction (transaction-id
  horizon).** Correct but couples the contract to PostgreSQL internals and long
  transactions stall the feed. Rejected for Release 1.
- **Historical as-of-watermark snapshot.** Requires per-SKU state history for 24 hours
  without improving correctness under the revision guard. Rejected.

## Consequences

- One small single-writer component and one extra table; publication gains sequencer
  latency (target well under one second).
- Kafka order and change-feed order follow commit order of sequencing, still without a
  global ordering promise to consumers beyond the documented guards.
- Snapshot sessions are cheap: no materialized copy or history is kept.

## Migration impact

No implementation or consumer exists. Update [data.md](../data.md),
[interfaces.md](../interfaces.md), [operations.md](../operations.md), the Export
OpenAPI, the Inventory AsyncAPI/README and Search's recovery wording that implied
values exactly at the watermark.

## Verification

- Concurrency test: interleaved commits of writers with out-of-order sequence
  acquisition never let a feed reader that advanced past `N` miss a fact.
- Sequencer failover: a fenced stale instance cannot assign or publish.
- Snapshot handoff: mutations during a paginated snapshot plus feed replay converge to
  the current revision for every registered SKU.
- Freshness: sequencing plus relay stays within the 60-second Search SLO under load.
