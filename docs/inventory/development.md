# Inventory Service — Development Readiness

> **Status:** Implementation intentionally deferred. This document prevents target design from being replaced by assumptions from the old service.

## Preconditions to start implementation

Implementation may start only after Inventory, Order, Checkout, Search, Warehouse Operations, Platform, and Security review the target documents and nominate accountable owners for their contracts.

- [ ] Contract owners accept [interfaces.md](interfaces.md), including idempotency, `REJECT_ALL`, event consumers, and the 90-day breaking-change policy.
- [ ] Platform demonstrates a relational-primary deployment proposal capable of atomic state/audit/command/outbox commit, RPO=0 target, and RTO <= 60 minutes.
- [ ] Compliance/Legal validates the seven-year audit/evidence retention policy.
- [ ] Load-test plan exercises the agreed envelope: 600 Reserve attempts/minute, p95 eight items/order, up to eight warehouses/SKU, 10% hot-SKU traffic, and 9,000 concurrent reservations as an estimated baseline.
- [ ] Failure test plan covers primary loss, broker outage, duplicate commands/events, deadlock, stale preview, release/dispatch race, late payment, and return/inspection retries.
- [ ] Operational dashboards, alert routes, backup/restore rehearsal, and Search snapshot/replay integration are planned before production launch.

## Implementation guardrails

1. No direct data migration or operational script may establish a second write path; all cutover stock loading uses `RecordSellableStockReceipt(INITIAL_STOCK_LOAD)` with approved baseline batch/audit.
2. No Redis cache, broker, or Search index may be used to decide Reserve/Commit/Release correctness.
3. Code must preserve transaction boundaries and lock order in [data.md](data.md), not recreate them with distributed compensation.
4. Tests must assert invariant failures cannot commit partial state, and same command/event identity cannot cause a second business effect.
5. Repository code, schemas, deployment manifests, and contracts are implementation artifacts; when they conflict with this target documentation, the target documentation wins until an accepted ADR changes it.

## Required verification before release

- Domain/property tests for all bucket movements, Refund Scope counters, state guards, and correction restrictions.
- Concurrency/load tests proving exact-plan all-or-nothing Reserve and no oversell under hot-SKU contention.
- Contract tests for every caller/consumer, schema compatibility, retry/idempotency, snapshot/change-feed recovery, and event dedupe.
- Restore/failover drills proving RPO=0/RTO <= 60 minutes and post-recovery reconciliation.
- Security tests for ADMIN MFA, workload authorization, audit immutability, and prohibited direct writes.
