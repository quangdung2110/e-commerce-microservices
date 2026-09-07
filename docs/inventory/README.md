# Inventory Service

> **Status:** Target design accepted for Release 1.
>
> **Implementation status:** Not started; existing service code is not a design constraint.

Inventory is the sole owner of warehouse master data, stock at each warehouse, the
derived per-SKU stock-availability integration state, reservations, physical dispatch
facts, return receipt, inspection/quarantine, stock adjustment, and their immutable
audit evidence. It does not own customer orders, payment, shipment/delivery, product
catalog, or Search's indexed copy.

## Release 1 in one page

| Topic | Binding decision |
|---|---|
| Correctness | No oversell is absolute. Only Inventory's authoritative primary store can accept a stock transition. |
| Reserve | A whole order is all-or-nothing. One unavailable line returns `REJECT_ALL`; there is no backorder or partial reservation. |
| Allocation | Each order line has exactly one warehouse for its entire quantity. Different lines may use different warehouses. |
| Payment and dispatch | Payment moves `reserved → committed`; only physical dispatch reduces `on_hand`. Order owns payment and delivery state. |
| Timeout | Order Temporal Saga owns the 15-minute timeout and explicit release. Inventory alerts at 60 minutes but never auto-releases. |
| Return | Order registers an immutable, whole-order Refund Scope before receipt. A returned item is saleable only after final inspection `PASS`. |
| Warehouse operation | `ADMIN` alone records receipt, adjustment, dispatch, return receipt, inspection, disposition, and warehouse configuration. |
| Integration | Transactional outbox is mandatory. Events are at-least-once. Inventory derives the per-SKU boolean consumed by Search; Search is never a checkout dependency. |
| Degradation | If authoritative state or the atomic audit/outbox transaction is unavailable, mutations fail closed and Preview returns `503 INVENTORY_UNAVAILABLE`. |

## Ownership boundary

| Owner | Owns | Inventory deliberately does not own |
|---|---|---|
| Inventory | Warehouse, `StockPosition`, `SkuAvailabilityState`, reservation/allocation, physical dispatch fact, return/inspection, movements, and audit. | Order/customer lifecycle, payment result, refund payout, shipment or delivery state. |
| Order + Temporal Saga | Order state, payment timeout/retry, release/commit decision, Refund Scope registration/closure, customer exception. | Direct stock mutation or warehouse operation. |
| Checkout | Advisory allocation preview and initiating its Order workflow. | Authoritative reserve decision. |
| Product | SKU/product lifecycle. | Warehouse stock; Inventory accepts opaque `sku_id` and never synchronously calls Product. |
| Search | Indexed copy of Inventory's per-SKU `is_in_stock` snapshot and buyer filtering. | Deriving warehouse eligibility, allocation, reservation, raw stock buckets, mutation. |
| Warehouse Operations | Physical truth submitted through the ADMIN contract. | Direct database editing or Order refund closure. |

## Target documentation

| Document | Source of truth for |
|---|---|
| [domain.md](domain.md) | Vocabulary, stock buckets, aggregates, lifecycle state machines, invariants, and warehouse-operation rules. |
| [workflows.md](workflows.md) | Happy path, timeout/retry/race/failure flows, refund closure, and transition ownership. |
| [interfaces.md](interfaces.md) | HTTP/workflow commands, queries, error semantics, events, Search snapshot/replay, and compatibility. |
| [data.md](data.md) | Logical records, movement evidence, transaction/locking/idempotency/outbox/recovery data rules. |
| [architecture.md](architecture.md) | Components, trust boundaries, authoritative write path, concurrency, and deployment constraints. |
| [operations.md](operations.md) | Failure modes, SLOs, reconciliation, security, DLQ/replay, backup, and DR runbook. |
| [decisions/README.md](decisions/README.md) | Current ADR index and status of historical ADRs. |
| [development.md](development.md) | Preconditions and verification plan before implementation starts. |

The working record that led to this target design is retained in [brainstorm/redesign-plan.md](brainstorm/redesign-plan.md). It is evidence, not a competing source of truth.

## Release 1 scope

Included: warehouse management; initial/supplier sellable receipt; adjustment `+/-`;
order reservation/commit/release; physical dispatch; immutable Refund Scope;
multi-package return receipt; inspection; quarantine disposition/reversal; asynchronous
per-SKU availability projection, Search readiness registration and export; audit,
reconciliation, and recovery.

Excluded: warehouse transfer; bins/lots/serials/expiry tracking; WMS and supplier/PO integration; backorder; partial order reservation; split quantity of one order line; automatic reroute after reserve failure; automatic reservation cleanup; automatic correction; customer-facing Inventory write API; and direct database repair as an operational process.

## Acceptance priorities and targets

Priority order is **no oversell**, auditability, operational simplicity, checkout availability, checkout latency, throughput, then Search freshness. Baseline targets to prove before production are Reserve p95/p99 <= 200/500 ms, Preview <= 300/750 ms, Search freshness <= 60 seconds, RPO = 0 for committed mutations, and RTO <= 60 minutes. They are release gates requiring load and DR evidence, not claims about the current implementation.

## Non-negotiable rules

1. `available_to_sell + reserved + committed + quarantine = on_hand`; every bucket is non-negative.
2. No caller writes Inventory persistence directly. Cache, Redis, broker ordering, and Search have no authority to accept or reject a stock transition.
3. Each accepted mutation atomically commits business state, immutable movement/audit evidence, command result, and outbox record—or commits none.
4. A business rejection is a valid terminal result; a lock/database/outbox failure is a retryable technical failure, never disguised as out of stock.
5. Correction uses an explicit audited command. It cannot silently remove reserved, committed, or quarantined units.
6. Inventory does not publish or export a SKU availability fact until Search has
   registered that the Product-owned SKU document exists; missing readiness never blocks
   an authoritative Inventory command.
