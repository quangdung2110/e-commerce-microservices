# Inventory Service — Data and Consistency Design

> **Status:** Target design accepted for Release 1.
>
> **Authoritative write source:** one relational primary database.

## 1. Data ownership and classification

Inventory owns the records below. State, command identity, immutable ledger/audit, and transactional outbox are one authoritative recovery unit. Cache, Search projection, relay leases, and consumer state are rebuildable and cannot authorize a mutation.

| Record | Key / uniqueness | Role |
|---|---|---|
| `Warehouse` | `warehouse_id` | Address, active/outage state, fulfillment priority. |
| `StockPosition` | `(warehouse_id, sku_id)` | Current buckets and position revision. |
| `OrderReservation` | `order_id` | Root state, `expires_at`, correlation. |
| `ReservationLine` | `(order_id, order_line_id)` | Immutable SKU, quantity, selected warehouse, lifecycle. |
| `RefundScope` | `(order_id, refund_request_id)` | Scope state, active-lock uniqueness per order. |
| `RefundScopeLine` | `(refund_scope_id, order_line_id)` | Immutable expected quantity and received/pass/fail counters. |
| `ReturnReceiptLine` | `(refund_scope_id, return_receipt_id, return_receipt_line_id)` | Physical inbound provenance and residual final-inspection quantity. |
| `InspectionResult` | Inspection/receipt-line operation identity | Final pass/fail evidence at receiving warehouse. |
| `QuarantineDisposition` | `(warehouse_id, disposition_id, disposition_line_id)` | Hold/outbound disposition and reversal trace. |
| `StockMovement` | `movement_id`; immutable | Bucket delta, original/reversal linkage, source command. |
| `AuditEntry` | append-only | Actor, role, reason/evidence, before/after values, causation. |
| `CommandExecution` | `(caller_namespace, command_id)` | Input fingerprint and terminal accepted/rejected outcome. |
| `OperationIdentity` | Business operation key | Enforces one accepted physical/business operation. |
| `OutboxEvent` | `event_id`, export sequence | Durable public/export event pending or completed relay. |

Current state and append-only evidence serve different purposes: state decides a command; movements/audit explain and reconcile it. Release 1 is not event sourced.

## 2. Integrity constraints

The database enforces, or the transaction guards before commit enforce:

- every StockPosition has non-negative buckets and `available_to_sell + reserved + committed + quarantine = on_hand`;
- a reservation line belongs to exactly one root and one warehouse; a successful Reserve root contains every requested line;
- one Order has at most one active Refund Scope (`REGISTERED`, `IN_PROGRESS`, `INSPECTION_COMPLETED`);
- scope counters satisfy `0 <= passed + failed <= received <= expected`;
- a return receipt cannot exceed the immutable line residual or be accepted after scope closure;
- final inspection quantity is consumed exactly once from the exact receiving warehouse receipt line;
- every accepted mutation has a movement/audit record when it changes stock and an outbox record when it produces a public/export fact;
- a command ID reused with a different canonical request fingerprint fails deterministically.

## 3. Command and operation idempotency

`CommandExecution` stores a durable terminal result for every command with key `(caller_namespace, command_id)` and canonical request fingerprint. It records both accepted results and domain rejections. A retry after unknown transport outcome therefore returns the same result without a second mutation.

`OperationIdentity` is a separate business key. Examples are `(warehouse_id, receipt_id, receipt_line_id)`, `(warehouse_id, adjustment_case_id, adjustment_line_id)`, `(refund_scope_id, return_receipt_id, return_receipt_line_id)`, and `(original_movement_id, reversal_sequence)`. It is claimed only by a successful operation. A rejected command must not poison a corrected later attempt with a new command ID.

## 4. Transaction boundary

One accepted command transaction performs, in order:

1. claim/read `CommandExecution` and return its prior result if present;
2. lock and revalidate target aggregates/identities;
3. apply every permitted state and bucket transition;
4. append `StockMovement` and `AuditEntry` evidence;
5. claim successful `OperationIdentity` when applicable;
6. persist the terminal command result and every `OutboxEvent` required by the state change;
7. commit once.

No broker, HTTP call, cache call, or external evidence-store call is inside this transaction. If any required step fails, it rolls back completely. Expected domain `REJECT_ALL` is durable but has no stock movement; technical failures leave no accepted result and can retry.

### Reserve transaction

Inventory groups planned quantities by `(warehouse_id, sku_id)`, locks the exact selected Warehouse/StockPosition rows in a stable order, revalidates activity/outage and full-line availability, then creates the root/lines and all bucket deltas in one commit. It does not rerank or choose an alternate warehouse after its preview plan fails.

### Required lock order

1. `CommandExecution` and `OperationIdentity`;
2. Warehouse only when configuration/allocation needs it;
3. OrderReservation root and lines;
4. Refund Scope, line, receipt, inspection, and disposition records;
5. StockPosition rows in `(warehouse_id ASC, sku_id ASC)` order.

The target isolation baseline is `READ COMMITTED` with explicit row locking and conditional guards. Deadlock, serialization conflict, or lock timeout aborts all work; Inventory retries internally at most twice, then returns retryable `503 BUSY`.

When warehouse configuration and Reserve race, the first committed transaction determines the result: a status change first makes Reserve reject the whole order; a Reserve first creates a commitment that later status changes do not revoke.

## 5. Outbox, consumers, and retention

The outbox is written in the command transaction and relayed at least once. Each event has immutable `event_id`, schema version, aggregate revision, correlation/causation IDs, and increasing export sequence. The relay leases bounded durable batches, retries with exponential backoff and jitter (maximum interval five minutes), and never reruns business movement to recreate an event.

Consumers keep their own inbox/dedupe record atomically with their effect. They can receive duplicates and events from unrelated aggregates in arbitrary order. A consumer-specific DLQ retains original event identity, payload/reference, attempts, and failure; replay goes through normal inbox processing.

Public/export events are retained for at least 30 days from durable creation. An undelivered event remains durable past ordinary cleanup. If retained outbox growth threatens capacity or the replay guarantee, Inventory fails new mutations closed rather than dropping evidence.

## 6. Cache, projection, and recovery classification

| Data | Classification | Recovery |
|---|---|---|
| Primary state, command/operation identity, movement/audit, outbox | Authoritative | Restore/fail over as a single consistent point. |
| Allocation/read cache | Optimization only | Discard and rebuild from primary; never use on correctness path. |
| Search projection | External derived state | Snapshot at watermark then replay change feed. |
| Relay leases/cursors | Ephemeral | Clear/rebuild; relay reprocesses durable outbox safely. |

Backups must include every authoritative record class. Losing command or operation identity would turn a late retry into a new mutation. Restore validation requires bucket scan, ledger checkpoint replay, uniqueness checks, outbox/export sequence validation, and access/audit health before writes reopen.

## 7. Retention and privacy

Immutable movements, audit, command/operation identities, and evidence references remain for at least seven years. External evidence content is retained/access-controlled for the same period but is not copied into events or routine logs. Current active state remains while operationally needed; archive/disposal must preserve ledger traceability. Retention is a Compliance/Legal production gate, not an excuse to weaken audit.
