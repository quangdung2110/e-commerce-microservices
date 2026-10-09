# Inventory Service — Development Readiness

> **Status:** Implemented in [`java/services/inventory-service`](../../java/services/inventory-service/README.md)
> after the owner accepted the target documents and the pre-implementation corrections of
> [ADR-027](decisions/ADR-027-pre-implementation-contract-corrections.md). The production
> gates below remain open. This document still prevents target design from being replaced
> by assumptions from the old service.

## Implementation status

| Area | Evidence in the repository |
|---|---|
| Runtime baseline (ADR-023) | Java 21, Spring Boot 3.3, PostgreSQL 16, Flyway `V1__inventory_baseline.sql`, explicit SQL on the locked write path, Debezium connector `src/main/resources/kafka/debezium-connector-inventory-outbox.json`. |
| Command transaction (data.md §4) | `CommandExecutor`: command claim, write guard, savepoint, durable accepted/rejected result, `lock_timeout` 2 s, two internal retries then `503 INVENTORY_BUSY`. Bucket equation and scope counters are database CHECK constraints; movement/audit are append-only by trigger. |
| Workflow, ADMIN, Read and Export surfaces | All operations of the four OpenAPI documents, `X-Service-Identity` allowlist and `X-User-Roles: ADMIN`. |
| Workers | Availability projector, warehouse fan-out, readiness publisher, fenced export sequencer, detection-only reconciliation (hourly bucket scan, daily ledger replay, 5-minute orphan scan, continuous availability audit), retention and outbox backpressure. |
| Automated tests | Domain/property tests and Testcontainers integration tests listed in the service README, including emitted CloudEvents validated against `contracts/events/inventory/v1`. |
| Relay smoke test | Throwaway PostgreSQL + Kafka + `debezium/connect:3.0.0.Final`: `published_event` rows reach `t.inventory.stock.events.v1`/`t.inventory.order.events.v1` with the documented key and full sequenced CloudEvent. |

## Preconditions before production

The original start preconditions (contract owner review and nominated owners) are
satisfied for implementation by the owner's acceptance of ADR-027; the remaining items
below are production gates.

- [x] Contract owners accept [interfaces.md](interfaces.md), including idempotency, `REJECT_ALL`, the BFF availability read, event consumers, and the 90-day breaking-change policy.
- [ ] Platform demonstrates a relational-primary deployment proposal capable of atomic state/audit/command/outbox commit, RPO=0 target, and RTO <= 60 minutes.
- [ ] Compliance/Legal validates the seven-year audit/evidence retention policy.
- [ ] Platform provisions `t.inventory.stock.events.v1` and `t.inventory.order.events.v1` with `retention.ms` = 30 days (floor and ceiling, ADR-028) and Search sizes its tombstone protection window strictly above it; the local compose relies on broker auto-creation and does not meet this.
- [ ] Before production: an Auth/gateway ADR issues and forwards an MFA assertion for ADMIN, and Inventory verifies it on every ADMIN mutation ([ADR-024](decisions/ADR-024-admin-surface-and-error-catalog.md)).
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

Covered by the automated suite today: exact-plan all-or-nothing Reserve and hot-SKU
no-oversell; same-ID replay, `IDEMPOTENCY_KEY_REUSED` and durable rejected-result replay
with command query `REJECTED`; Commit after `expires_at`; recovery only for
`RESERVATION_EXPIRED` including a stock shortfall; pre-dispatch release versus dispatch race;
whole-line dispatch; Refund Scope dispatch guard, lifetime uniqueness, multi-package mixed
PASS/FAIL completion once, close/close-incomplete/cancel guards; HOLD keeps quantity
disposable and disposition reversal returns to quarantine only; reversal sequence and
cumulative limits; BFF `UNKNOWN` for missing or > 30 s pending work including warehouse
fan-out; no publication before readiness, registration publishes the current snapshot,
stale revocation conflict; fenced stale sequencer; snapshot plus change feed; write freeze;
emitted CloudEvents valid against their schemas. The audit corrections of
[ADR-028](decisions/ADR-028-post-implementation-audit-corrections.md) are covered by
`AuditCorrectionsIT`: identity checks on encoded and `;` paths, a snapshot watermark that
survives a full retention purge, concurrent duplicate-order Reserve, replayed scope
registration, the availability audit and snapshot-session expiry codes. Load,
failover/restore, MFA and cross-service consumer contract tests remain open.

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
- ADMIN gates under
  [ADR-024](decisions/ADR-024-admin-surface-and-error-catalog.md): path
  `/api/v1/admin/inventory` with role check; every operational query paginates,
  filters and exposes no PII; dispatch rejects partial quantity; `HOLD` keeps
  quantity disposable; reversal limits hold; every `409` carries a catalog `code`; and
  command queries report `REJECTED` outcomes.
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
- Contract tests for every caller/consumer, including: BFF batch response has one result per requested SKU, exposes no quantity/warehouse/reservation, returns `UNKNOWN` for missing state or pending work older than 30 s and the last state otherwise (ADR-025), and authorizes only BFF; no per-SKU availability publish/export before Search readiness; registration emits the current snapshot; warehouse state changes durably fan out all affected SKUs; revision-safe snapshot/change-feed recovery; and no Search-side upsert/index.
- Restore/failover drills proving RPO=0/RTO <= 60 minutes and post-recovery reconciliation.
- Security tests for the ADMIN role check (and, before production, the MFA assertion from the Auth/gateway ADR required by [ADR-024](decisions/ADR-024-admin-surface-and-error-catalog.md)), workload authorization, audit immutability, and prohibited direct writes.
