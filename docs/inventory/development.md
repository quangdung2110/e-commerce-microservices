# Inventory Service — Development Readiness

> **Status:** Implementation intentionally deferred. This document prevents target design from being replaced by assumptions from the old service.

## Preconditions to start implementation

Implementation may start only after Inventory, Order, Checkout, Search, Warehouse Operations, Platform, and Security review the target documents and nominate accountable owners for their contracts.

- [ ] Contract owners accept [interfaces.md](interfaces.md), including idempotency, `REJECT_ALL`, the BFF availability read, event consumers, and the 90-day breaking-change policy.
- [ ] Platform demonstrates a relational-primary deployment proposal capable of atomic state/audit/command/outbox commit, RPO=0 target, and RTO <= 60 minutes.
- [ ] Compliance/Legal validates the seven-year audit/evidence retention policy.
- [ ] Load-test plan exercises the agreed envelope: 600 Reserve attempts/minute, p95 eight items/order, up to eight warehouses/SKU, 10% hot-SKU traffic, and 9,000 concurrent reservations as an estimated baseline.
- [ ] Failure test plan covers primary loss, broker outage, duplicate commands/events, deadlock, stale preview, release/dispatch race, fenced late payment recovery (including every rejected guard), and return/inspection retries.
- [ ] Operational dashboards, alert routes, backup/restore rehearsal, BFF availability read load/freshness tests, and Search per-SKU availability readiness/snapshot/change-feed integration are planned before production launch.

## Runtime baseline

[ADR-023](decisions/ADR-023-java-spring-boot-postgresql-runtime.md) fixes the stack:
Java 21 and Maven, Spring Boot 3.x, PostgreSQL 16 with explicit SQL on the locked write
path, Flyway migrations, Kafka with a Debezium connector on `PublishedEvent`, leased
in-service workers (availability projector, warehouse fan-out, readiness publication,
single fenced export sequencer), JUnit 5 and Testcontainers. Code lives in
`java/services/inventory-service`, package `com.ecommerce.inventory`.

## Implementation guardrails

1. No direct data migration or operational script may establish a second write path; all cutover stock loading uses `RecordSellableStockReceipt(INITIAL_STOCK_LOAD)` with approved baseline batch/audit.
2. No Redis cache, broker, or Search index may be used to decide Reserve/Commit/Release correctness.
3. Code must preserve transaction boundaries and lock order in [data.md](data.md), not recreate them with distributed compensation.
4. Tests must assert invariant failures cannot commit partial state, and same command/event identity cannot cause a second business effect.
5. Repository code, schemas, deployment manifests, and contracts are implementation artifacts; when they conflict with this target documentation, the target documentation wins until an accepted ADR changes it.

## Required verification before release

- Command-result/publication contract gates under
  [ADR-020](decisions/ADR-020-command-receipts-and-minimal-order-events.md): exactly two
  Order-facing public types; no reservation-result publication; same-ID query/replay
  after response loss/crash; durable repair after retry exhaustion; distinguish original
  command receipt from current reservation state; preserve affected-SKU dirty markers
  for Reserve/Release/recovery and atomic dispatch/inspection outbox creation.
- Lifecycle gates under
  [ADR-021](decisions/ADR-021-order-integration-lifecycle-corrections.md): Commit after
  `expires_at` on a `RESERVED` root succeeds; Commit on a non-`RESERVED` root is a
  durable `409`; every release reason releases the whole root and is stored immutably;
  no single-line or `STOCK_DISCREPANCY` release exists; Refund Scope registration
  rejects non-`DISPATCHED` lines; Reserve never looks up `allocation_plan_id`.
- Export gates under
  [ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md): out-of-order commits
  never let a change-feed reader skip a fact; a fenced stale sequencer cannot publish;
  snapshot plus feed converge to the current revision while mutations run.
- Domain/property tests for all bucket movements, Refund Scope counters, state guards, and correction restrictions.
- Scope-finalization contract/concurrency tests: completed close after financial success
  or terminal failure; incomplete close only after all received final-inspected;
  zero-receipt cancel; duplicate/response-loss command replay; durable rejected result
  replay after state progression; final receipt/inspection race; terminal scope cannot
  reopen after Payment reversal or admit another lifetime request. Coordinate with
  [Order ADR-013](../order/decisions/ADR-013-durable-refund-scope-finalization.md).
- Concurrency/load tests proving exact-plan all-or-nothing Reserve and no oversell under hot-SKU contention.
- Contract and concurrency tests proving late-payment recovery only commits the original
  `RESERVATION_EXPIRED` root as one whole root, preserves its durable replay outcome,
  and rejects every other release reason, dispatch state, stock shortfall, or reroute
  attempt with no movement.
- Contract tests for every caller/consumer, including: BFF batch response has one result per requested SKU, exposes no quantity/warehouse/reservation, returns `UNKNOWN` for dirty/missing state, and authorizes only BFF; no per-SKU availability publish/export before Search readiness; registration emits the current snapshot; warehouse state changes durably fan out all affected SKUs; revision-safe snapshot/change-feed recovery; and no Search-side upsert/index.
- Restore/failover drills proving RPO=0/RTO <= 60 minutes and post-recovery reconciliation.
- Security tests for ADMIN MFA, workload authorization, audit immutability, and prohibited direct writes.
