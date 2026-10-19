# Inventory Service — Data and Consistency Design

> **Status:** Target design accepted for Release 1.
>
> **Authoritative write source:** one relational primary database.

## 1. Data ownership and classification

Inventory owns the records below. State, command identity, immutable ledger/audit, and transactional outbox are one authoritative recovery unit. Cache, Search projection, relay leases, and consumer state are rebuildable and cannot authorize a mutation.

| Record | Key / uniqueness | Role |
|---|---|---|
| `Warehouse` | `warehouse_id` (Inventory-generated) | Display `name`, address, active/outage state, fulfillment priority and `revision` used by the update guard. |
| `StockPosition` | `(warehouse_id, sku_id)` | Current buckets and position revision. |
| `SkuAvailabilityState` | `sku_id` | Inventory-owned derived boolean, monotonic `availability_revision` and `evaluated_at`; source for Search publication and BFF reads. |
| `SkuAvailabilityDirty` | `sku_id` | Durable coalescing signal with `first_marked_at` (oldest unprocessed mark, never reset by coalescing) and a mark sequence the projector clears up to its start. |
| `AvailabilityFanoutJob` | `fanout_job_id`; one active job per `(warehouse_id, warehouse_revision)` | Checkpointed scan of distinct SKU IDs at a warehouse after an `is_active`/`operational_state` change: `created_at`, cursor, completion. |
| `SearchProjectionReadiness` | `sku_id`; greatest `catalog_revision` | Proof registered by Search that the Product-owned SKU document exists; publication/export gate only. |
| `ReadinessPublicationTask` | `(sku_id, catalog_revision)` | Durable task created atomically with a readiness registration to publish the current snapshot even without a later stock change. |
| `SnapshotSession` | `snapshot_id` | Search snapshot session: `export_watermark`, `created_at`, `expires_at` (24 h); holds no copy of state. The hourly retention pass deletes expired sessions; a read is `410` until then and `404` afterwards. |
| `OrderReservation` | `order_id` | Derived root state, `accepted_at`, advisory `expires_at`, stored `allocation_plan_id`, immutable `ReleaseReservation` reason (kept after recovery), separate committed-release reason `ADMIN_FORCE_REFUND_BEFORE_DISPATCH`, commit/recovery `payment_reference`, correlation. |
| `ReservationLine` | `(order_id, order_line_id)` | Immutable SKU, quantity, selected warehouse, line state (`RESERVED`/`COMMITTED`/`DISPATCHED`/`RELEASED`), `committed_at` and dispatch reference. |
| `PhysicalDispatch` | `(warehouse_id, dispatch_id, dispatch_line_id)`; one per `(order_id, order_line_id)` | Whole-line dispatch fact: handover reference/time, evidence reference, actor and movement link; source of the dispatch event and query fields. |
| `RefundScope` | `order_id` lifetime unique; `refund_request_id` unique | Immutable selected-line scope state for the one Order refund case. |
| `RefundScopeLine` | `(refund_scope_id, order_line_id)` | Immutable expected quantity and received/pass/fail counters. |
| `ReturnReceiptLine` | `(return_receipt_id, return_receipt_line_id)` globally unique; recorded with its `refund_scope_id` | Physical inbound provenance and residual final-inspection quantity. |
| `InspectionResult` | `inspection_id` globally unique; many per receipt line until its residual is zero | Final pass/fail evidence at receiving warehouse. |
| `QuarantineDisposition` | `(disposition_id, disposition_line_id)` globally unique; recorded with `warehouse_id` | Non-terminal `HOLD` annotation or outbound `DISPOSE`/`RETURN_TO_SUPPLIER` consuming failed, undisposed quantity, with reversal trace. |
| `StockMovement` | `movement_id`; immutable | Bucket delta, original/reversal linkage, source command. |
| `AuditEntry` | append-only | Actor, role, reason/evidence, before/after values, causation. |
| `CommandExecution` | `(caller_namespace, command_id)` | Input fingerprint and terminal outcome: `ACCEPTED` response or `REJECTED` HTTP status, catalog `code` and problem body for exact replay. |
| `OperationIdentity` | Business operation key | Enforces one accepted physical/business operation. |
| `OutboxEvent` | `event_id`; `outbox_id` | Public fact written in the business/projector transaction, not yet sequenced. |
| `PublishedEvent` | `event_id`; unique export `sequence` | Finalized CloudEvent with commit-ordered sequence; the only Kafka relay and change-feed source ([ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md)). |

Current state and append-only evidence serve different purposes: state decides a command; movements/audit explain and reconcile it. Release 1 is not event sourced.

## 2. Integrity constraints

The database enforces, or the transaction guards before commit enforce:

- every StockPosition has non-negative buckets and `available_to_sell + reserved + committed + quarantine = on_hand`;
- a reservation line belongs to exactly one root and one warehouse; a successful Reserve root contains every requested line;
- one Order has at most one Refund Scope in its entire retained history; a terminal
  scope is never deleted/replaced to permit another `refund_request_id`;
- every registered `RefundScopeLine.order_line_id` must be a distinct immutable
  ReservationLine of the order in line state `DISPATCHED`, with
  `expected_quantity = ReservationLine.quantity`; a selected scope is allowed to omit
  other lines but cannot split a selected line;
- a reservation root never mixes released lines with committed or dispatched lines;
  every release is whole-root;
- scope counters satisfy `0 <= passed + failed <= received <= expected`;
- a return receipt cannot exceed the immutable line residual or be accepted after scope closure;
- final inspection quantity is consumed exactly once from the exact receiving warehouse receipt line;
- every accepted stock mutation has a movement/audit record and durably marks each
  affected SKU for availability recomputation; the projector, not the command hot path,
  creates the Search-facing public fact;
- a SKU availability fact/export row exists only when a matching active
  `SearchProjectionReadiness` exists;
- a command ID reused with a different canonical request fingerprint fails deterministically.

## 3. Command and operation idempotency

`CommandExecution` stores a durable terminal result for every command with key `(caller_namespace, command_id)` and canonical request fingerprint. It records both accepted results and domain rejections. A retry after unknown transport outcome therefore returns the same result without a second mutation.

`OperationIdentity` is a separate business key. Examples are `(warehouse_id, receipt_id, receipt_line_id)`, `(warehouse_id, adjustment_case_id, adjustment_line_id)`, `(return_receipt_id, return_receipt_line_id)`, `inspection_id`, `(disposition_id, disposition_line_id)`, `(warehouse_id, dispatch_id, dispatch_line_id)`, `(original_movement_id, reversal_sequence)` and `(disposition_id, disposition_line_id, reversal_sequence)`; `reversal_sequence` is contiguous ([ADR-027](decisions/ADR-027-pre-implementation-contract-corrections.md)). It is claimed only by a successful operation. A rejected command must not poison a corrected later attempt with a new command ID.

## 4. Transaction boundary

One accepted command transaction performs, in order:

1. claim/read `CommandExecution` and return its prior result if present;
2. lock and revalidate target aggregates/identities;
3. apply every permitted state and bucket transition;
4. append `StockMovement` and `AuditEntry` evidence;
5. claim successful `OperationIdentity` when applicable;
6. persist the terminal command result, public outbox facts required by the documented
   publication policy, and durable per-SKU availability dirty markers required by the
   state change;
7. commit once.

No broker, HTTP call, cache call, or external evidence-store call is inside this transaction. If any required step fails, it rolls back completely. Expected domain `REJECT_ALL` is durable but has no stock movement; technical failures leave no accepted result and can retry.

Reservation commands (Reserve, Commit, Release, late-payment recovery, pre-dispatch release) order steps 3 and 4 the other way round for `StockPosition` ([ADR-033](decisions/ADR-033-stock-position-lock-taken-last.md)): they write root/line state, movements and audit first, the executor records the command result, and only then is each bucket delta applied as one guarded update (`StockStore.applyGuarded`: relative delta, every bucket must stay non-negative); the last position's update also writes the SKU dirty markers in the same statement ([ADR-034](decisions/ADR-034-command-result-before-stock-and-dirty-mark-in-last-update.md)). The guarded update is what takes the position row lock, so for a one-line order a hot SKU is held only for that one statement and the commit. A Reserve shortfall found there rolls back to its savepoint, the recorded result included, and records `REJECT_ALL` instead. ADMIN, receipt, dispatch and return commands still lock the position first.

Reserve/Commit/Release/recovery results are retained in `CommandExecution` and returned
as HTTP receipts; none creates a reservation-result public event. Reserve/Release and
successful recovery still atomically mark affected SKUs when sellable quantity changes.
Normal Commit changes only reserved/committed buckets. Internal audit/work records do
not become broker messages. Physical dispatch and inspection completion retain their
required public outbox records ([ADR-020](decisions/ADR-020-command-receipts-and-minimal-order-events.md)).

### Reserve transaction

Inventory groups planned quantities by `(warehouse_id, sku_id)` and share-locks the selected Warehouse rows in a stable order. An inactive/outage warehouse or a missing position rejects the whole order before anything is written. Otherwise Reserve sets a savepoint, creates the root, lines, movements and audit, and then applies each position's reserve delta as a guarded update in `(warehouse_id, sku_id)` order, last ([ADR-033](decisions/ADR-033-stock-position-lock-taken-last.md)). If a guard finds too little `available_to_sell`, Reserve rolls back to the savepoint, locks the positions as before ADR-033, and returns `REJECT_ALL` naming every unavailable line, with no root, line, movement, audit or bucket change. If the locked positions turn out sufficient (stock freed concurrently), it writes again under those locks. It does not rerank or choose an alternate warehouse after its preview plan fails. `allocation_plan_id` is stored on the root but never looked up, because Preview persists no plan.

Commit locks the root and lines and requires only root state `RESERVED`; it does not compare the clock with `expires_at`. Release stores its reason immutably on the root ([ADR-021](decisions/ADR-021-order-integration-lifecycle-corrections.md)). Both update the root and lines and write audit and movements before their guarded position updates; a guard that fails there means the reservation is no longer covered by its buckets, an invariant breach that rolls the command back.

Reserve first takes a transaction-scoped lock keyed by `order_id`, then looks for an existing root. Two Reserve commands for one order under different command IDs therefore run one after the other, and the second answers `RESERVATION_ALREADY_EXISTS` instead of evaluating stock the first already reserved ([ADR-028](decisions/ADR-028-post-implementation-audit-corrections.md)).

### Required lock order

1. `CommandExecution` (claimed first; a concurrent same-key request waits on it) and, for Reserve, the per-order key. `OperationIdentity` is checked first with an unlocked read and claimed by insert once the target aggregates are locked: the unique key makes a concurrent winner the durable `OPERATION_IDENTITY_CONFLICT`, and the claim cannot form a wait cycle because two commands with the same key already contend on the same aggregate rows, locked in the order below;
2. Warehouse only when configuration/allocation needs it;
3. OrderReservation root and lines;
4. Refund Scope, line, receipt, inspection, and disposition records;
5. StockPosition rows in `(warehouse_id ASC, sku_id ASC)` order.

SKU availability dirty markers are taken after the StockPosition rows by every command; the availability projector never locks a position, so the two cannot wait on each other. In reservation commands the StockPosition locks come from the guarded updates at the end of the transaction rather than from an earlier `SELECT … FOR UPDATE` ([ADR-033](decisions/ADR-033-stock-position-lock-taken-last.md)); the order among them is the same.

The target isolation baseline is `READ COMMITTED` with explicit row locking and conditional guards. Every transaction runs with `lock_timeout = 2 s`. Deadlock, serialization conflict, or lock timeout aborts all work; Inventory retries internally at most twice, then returns retryable `503 INVENTORY_BUSY` with `Retry-After: 1` ([ADR-027](decisions/ADR-027-pre-implementation-contract-corrections.md)).

When warehouse configuration and Reserve race, the first committed transaction determines the result: a status change first makes Reserve reject the whole order; a Reserve first creates a commitment that later status changes do not revoke.

### Atomic pre-dispatch order release

`ReleaseCommittedOrderBeforeDispatch` locks the OrderReservation root, all of its
ReservationLine rows, then their StockPositions in the required stable order. It
revalidates that every line is `COMMITTED` and no physical-dispatch fact exists
before changing any bucket. Success moves every line `COMMITTED -> RELEASED` and
`committed -> available_to_sell` in this one transaction. A mixed, released or
dispatched line is a durable `409` with no stock movement. No per-line committed
release exists ([ADR-021](decisions/ADR-021-order-integration-lifecycle-corrections.md)).

### Atomic late-payment recovery

`RecoverTimedOutPayment` locks the `OrderReservation`, every `ReservationLine`, and
the original `StockPosition` rows in the same stable order. The command must find the
root `RELEASED` with immutable release reason `RESERVATION_EXPIRED`, no dispatch fact,
and enough `available_to_sell` for every original line at its original warehouse. In
the same transaction it changes all lines/root to `COMMITTED`, moves the exact buckets
`available_to_sell -> committed`, appends movement/audit evidence tied to the verified
Payment reference, and records the terminal `CommandExecution` result. A failed guard
is a durable rejection with no movement. This is not a re-Reserve and cannot create a
new allocation plan or root.

### Refund Scope finalization transaction

Close/cancel locks the same RefundScope and line/receipt records as receipt/inspection.
`close` requires `INSPECTION_COMPLETED`; `close-incomplete` requires `IN_PROGRESS`,
every line `passed + failed = received`, and at least one missing expected quantity;
`cancel` requires `REGISTERED` with no receipt. Revalidate these guards in the command
transaction, then persist terminal scope state, close reason when the endpoint carries
one, command result, actor/correlation/causation and audit atomically. Buckets and
failed-quarantine disposition duties remain unchanged. No new public closure fact is
introduced; Order retains the command receipt as its completion evidence.

Accepted and rejected command outcomes retain normal idempotency rules. A delayed
retry returns its original outcome, even if the scope progressed since a rejection.
Only a corrected new Order decision may use a new command ID after proven rejection.
Terminal scope/Order lifetime identities remain retained; payment reversal never
reopens or replaces them.

## 5. Per-SKU availability projection

Every transaction that can change `available_to_sell` records the distinct affected SKU
IDs. Commit and physical dispatch do not mark a SKU when they leave
`available_to_sell` unchanged. A warehouse `is_active`/`operational_state` change creates
a checkpointed fan-out job over distinct SKU IDs with positions at that warehouse.

Workers claim dirty SKU IDs and serialize calculation per `sku_id`. They read the current
relational state and calculate:

```text
is_in_stock = exists active, non-OUTAGE warehouse
              with available_to_sell > 0 for the SKU
```

Every successful calculation records the latest `evaluated_at` on
`SkuAvailabilityState`. When the state is new or the boolean changes, the same projector
transaction increments `availability_revision` and writes its Search public/export outbox
fact. Quantity changes that keep the boolean unchanged emit no public fact.

The Availability Read API serves Product Detail BFF from this Inventory-owned state with
a 30-second freshness budget ([ADR-025](decisions/ADR-025-pdp-availability-staleness-budget.md)).
A SKU returns `UNKNOWN` when it has no completed state, or when its oldest pending work
is older than 30 seconds: `SkuAvailabilityDirty.first_marked_at`, or the `created_at` of
an unfinished `AvailabilityFanoutJob` for a warehouse where the SKU has a position and the
job's cursor has not yet reached it (once the job marks the SKU, its own dirty mark carries
the same age until the projector recomputes it).
Otherwise the response contains the last completed boolean, its `availability_revision`,
and `evaluated_at`, even while younger work is pending. The
API never exposes `available_to_sell`, raw quantity, warehouse, reservation, or outbox
cursor. It neither writes state nor depends on Search readiness.

The state may be calculated before Search readiness, but publication/export remains
pending. An idempotent readiness registration atomically stores the greatest Product
`catalog_revision` plus a durable publication task for the current snapshot; it returns
success only after both are durable. Only a new registration or a greater
`catalog_revision` creates the task; after revocation only a greater revision registers
again. A SKU with no state is evaluated from current stock (`false` when it has none) as revision 1 before publication. Search
revokes it after tombstone and before purge; an older revision cannot revoke a newer
lifecycle ([ADR-027](decisions/ADR-027-pre-implementation-contract-corrections.md)).

## 6. Outbox, consumers, and retention

The outbox is written in the command transaction and relayed at least once. Each event has immutable `event_id`, schema version, aggregate revision and correlation/causation IDs. The writing transaction assigns no export sequence: a single fenced sequencer later takes committed unsequenced `OutboxEvent` rows in `outbox_id` order and, in one short transaction per batch, assigns the next export sequence values and inserts the finalized CloudEvent into `PublishedEvent`. Because sequencer transactions are serialized and see only committed rows, sequence follows commit order and a reader past `N` never later sees a new fact `<= N` ([ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md)). Debezium relays `PublishedEvent` to Kafka with bounded retries and exponential backoff/jitter (maximum interval five minutes); the change feed reads the same table. Neither path reruns business movement to recreate an event.

Consumers can receive duplicates and events from unrelated aggregates in arbitrary
order. Order keeps its inbox/dedupe record atomically with its effect and owns a DLQ.
Search applies a strict per-SKU availability revision guard without upsert, retries
document-readiness violations without ACK, and uses snapshot/change-feed rebuild for
recovery.

Public/export events are retained for 30 days from durable creation (`inventory.outbox.retention`): the hourly retention pass deletes sequenced facts older than that, raises the retention floor in the same transaction, and never deletes an unsequenced `OutboxEvent`. The floor also bounds the snapshot watermark from below, so a purge of every fact cannot make a fresh snapshot's `after_sequence` answer `410`. Both Kafka topics are provisioned with `retention.ms` = 30 days as a floor **and** a ceiling, so consumers can size their own windows (for example Search's tombstone protection window) strictly above it ([ADR-028](decisions/ADR-028-post-implementation-audit-corrections.md)). An undelivered event remains durable past ordinary cleanup. If retained outbox growth threatens capacity or the replay guarantee, Inventory fails new mutations closed rather than dropping evidence: more than `inventory.outbox.max-unsequenced` (default 100 000) unsequenced `OutboxEvent` rows make every mutation return `503 INVENTORY_UNAVAILABLE` ([ADR-027](decisions/ADR-027-pre-implementation-contract-corrections.md)).

## 7. Cache, projection, and recovery classification

| Data | Classification | Recovery |
|---|---|---|
| Primary state, command/operation identity, movement/audit, outbox | Authoritative | Restore/fail over as a single consistent point. |
| Allocation/read cache | Optimization only | Discard and rebuild from primary; never use on correctness path. |
| `SkuAvailabilityState` | Inventory-owned availability read/integration projection | Recompute from Warehouse/StockPosition; reconcile continuously. It serves BFF advisory reads and feeds Search publication. |
| Search projection | External indexed copy | Apply registered current per-SKU snapshot rows, then replay change feed after the session watermark under the revision guard. |
| Relay leases/cursors | Ephemeral | Clear/rebuild; relay reprocesses durable outbox safely. |

Backups must include every authoritative record class. Losing command or operation identity would turn a late retry into a new mutation. Restore validation requires bucket scan, ledger checkpoint replay, uniqueness checks, outbox/export sequence validation, and access/audit health before writes reopen.

## 8. Retention and privacy

Immutable movements, audit, command/operation identities, and evidence references remain for at least seven years. External evidence content is retained/access-controlled for the same period but is not copied into events or routine logs. Current active state remains while operationally needed; archive/disposal must preserve ledger traceability. Retention is a Compliance/Legal production gate, not an excuse to weaken audit.
