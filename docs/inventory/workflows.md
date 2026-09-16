# Inventory Service — Workflows

> **Status:** Target design accepted for Release 1.
>
> **Rule:** A workflow command may retry; a state transition may not be applied twice.

## 1. Whole-order checkout and fulfillment

```mermaid
sequenceDiagram
  participant C as Checkout
  participant O as Order + Temporal Saga
  participant I as Inventory
  participant P as Payment
  participant A as Warehouse ADMIN

  C->>I: AllocationPreview(lines)
  I-->>C: ALLOCATABLE exact warehouse plan | REJECT_ALL | 503
  C->>O: Create order with preview plan
  O->>I: ReserveOrder(order, immutable lines, command ID)
  I-->>O: RESERVED | REJECT_ALL | retryable failure
  O->>P: Collect payment
  P-->>O: payment outcome
  alt payment succeeds before expiry
    O->>I: CommitPayment(command ID)
    I-->>O: COMMITTED
    A->>I: RecordPhysicalDispatch(line, warehouse)
    I-->>O: com.ecommerce.inventory.reservation.line-dispatched.v1
  else payment fails or Order timeout decides
    O->>I: ReleaseReservation(reason, command ID)
    I-->>O: RELEASED
  end
```

### Allocation and Reserve

1. Checkout requests an advisory Preview. Inventory excludes inactive/outage warehouses and selects a single eligible warehouse per line by `(fulfillment_priority, warehouse_id)`.
2. Preview success never promises stock. Checkout records the proposed plan in Order; Inventory does not create a hold.
3. Only Order Temporal Saga calls `ReserveOrder`. Inventory locks/revalidates the **exact** planned warehouse/line quantities.
4. If any line is unavailable or its plan became invalid, the entire command returns durable domain outcome `REJECT_ALL`. No reservation, no partial allocation, and no silent attempt of another warehouse remains.
5. A successful command creates one root and all lines in `RESERVED`, sets `expires_at = accepted_at + 15 minutes`, writes audit/movements/outbox atomically, and returns the exact durable result.

### Payment, release, and dispatch

- Payment never calls Inventory. Order Saga decides and sends `CommitPayment` exactly once in business effect; same command ID safely replays the outcome.
- Commit is accepted only for the entire `RESERVED` root before the deadline. It moves every line to `COMMITTED` atomically. A late *new* commit is rejected; a late retry of an already accepted commit returns success.
- On payment failure or timeout, Order calls `ReleaseReservation(PAYMENT_FAILED | RESERVATION_EXPIRED)`. Inventory does not run an expiry worker.
- If Payment later proves success after the timeout release, Order may call only
  `RecoverTimedOutPayment` with that payment reference. Inventory locks the original
  root/plan and atomically commits all lines only when the release reason was
  `RESERVATION_EXPIRED`, none dispatched, and all exact original stock is still
  available. It otherwise returns durable `409` without movement; Order must reconcile
  the financial fact rather than issue a new Reserve or a partial recovery.
- Warehouse ADMIN records physical dispatch only for the committed allocation at its assigned warehouse. Individual lines can dispatch independently after payment, so a root can become `PARTIALLY_DISPATCHED`; Order remains sole owner of shipment/delivery state.

### Failure F1 — store unavailable or stock unavailable

| Condition | Result | Caller action |
|---|---|---|
| Primary Inventory store unavailable | Preview/mutation returns `503 INVENTORY_UNAVAILABLE`; no cache plan or pending hold. | Retry using the same command ID after recovery. |
| Lock/deadlock/serialization remains after two internal retries | `503 BUSY`, never `OUT_OF_STOCK`. | Saga retries safely. |
| Exact plan cannot reserve a complete order | `200` terminal domain outcome `REJECT_ALL`. | End this checkout attempt or start a new customer interaction; do not reroute silently. |

### Failure F2 — timeout, retry, and explicit release

Order owns all timeout/retry decisions. At 15 minutes it issues `ReleaseReservation(RESERVATION_EXPIRED)` if no payment commit has succeeded. Inventory stores the deadline only to reject a new late commit and to observe aging. A root still `RESERVED` beyond 60 minutes produces an orphan alert to Order/operations; Inventory must neither delete nor release it.

`ReleaseReservation` is valid only while the whole root is `RESERVED`. `STOCK_DISCREPANCY` is allowed only with an `order_exception_id` and releases the entire root. No single-line reserved release exists.

### Failure F2.1 — verified late payment after timeout release

Order first verifies that the Payment success belongs to the original transaction, then
uses a stable recovery command ID. Inventory returns the stored command result after an
unknown transport outcome. A new recovery is accepted only under the narrow whole-root
guards above. `409` is final for this allocation and does not authorize re-reserve,
rerouting, automatic refund, or an inventory-side stock correction; Order persists a
financial-reconciliation/manual-review case.

### Failure F3 — release/cancel versus dispatch

The two contenders lock the same reservation line and its stock position. Whichever commits first changes the line; the second command revalidates and returns its already-applied/invalid-state outcome. There is no compensating hidden stock movement.

For a targeted committed-but-undispatched recovery, Order may call
`ReleaseCommittedAllocation`. Automatic admin refund must instead call
`ReleaseCommittedOrderBeforeDispatch`: one transaction locks all reservation lines
and affected positions, releases all only if every line is committed/undispatched,
or rejects with no movement. If any line is dispatched, automatic refund has no
partial-release fallback. Dispatched stock is never restored through either command.

### Failure F4 — outbox unavailable after business decision

Business state, command result, audit/movement, and outbox record share one database transaction. If that transaction succeeds but relay/broker fails, Inventory returns the accepted command result and later publishes the durable event at least once. If the outbox record cannot commit, the business command aborts. The relay never reruns a movement to recreate an event.

## 2. Refund, receipt, inspection, and closure

```mermaid
sequenceDiagram
  participant O as Order + Temporal Saga
  participant I as Inventory
  participant A as Warehouse ADMIN

  O->>I: RegisterRefundScope(selected full-quantity lines)
  I-->>O: REGISTERED
  loop one or more packages
    A->>I: RecordReturnReceipt(exact scope line)
    I-->>A: IN_PROGRESS; on_hand +q, quarantine +q
    A->>I: RecordInspectionPass or RecordInspectionFail
  end
  alt all expected units final inspected
    I-->>O: com.ecommerce.inventory.refund.all-items-inspection-completed.v1
    O->>I: CloseRefundScope(terminal Order reason)
    I-->>O: CLOSED
  else returns incomplete after Order decision
    O->>I: CloseIncompleteRefundScope(reason)
    I-->>O: CLOSED_INCOMPLETE
  end
```

- Before a warehouse receives any item, Order registers an immutable selected-line
  scope. Each selected line equals its original order quantity, and `order_id` has
  one scope for its full retained lifetime, not merely while active.
- ADMIN may accept many package receipts, but only exact scoped SKU/order-line residual quantity. Wrong/extra/over-receipt rejects the whole command with no stock mutation.
- Receipt is physical inbound to quarantine. Final `PASS` moves only that receipt-line quantity to `available_to_sell`; final `FAIL` stays quarantined.
- All expected units final inspected (pass **or** fail) creates one completion fact. Inventory cannot decide payout and cannot close the scope.
- `CloseIncompleteRefundScope` requires that all received units already have final inspection while some expected quantity is missing. It emits no completion event.

## 3. Warehouse discrepancy and correction

1. ADMIN records a verified count discrepancy with reason and evidence; it cannot negative-adjust stock that is reserved, committed, or quarantined.
2. If the affected order is `RESERVED`, Order records an exception and releases the complete root using `STOCK_DISCREPANCY`.
3. If it is committed or dispatched, Order makes its cancellation/post-dispatch exception decision first.
4. ADMIN submits the appropriate append-only adjustment or permitted reversal. Inventory writes its normal audit/movement/outbox transaction.
5. Reconciliation is rerun. A ledger/state mismatch is an incident, not an adjustment case.

## 4. Per-SKU availability derivation for Search and PDP BFF

Stock commands remain warehouse-specific: each receipt, adjustment, Reserve/Release,
inspection pass, and other transition that changes `available_to_sell` identifies the
affected `(warehouse_id, sku_id)`. The command transaction durably coalesces affected SKU
IDs but does not calculate or publish Search state synchronously.

The availability projector performs this workflow:

1. Claim dirty work serialized by `sku_id`.
2. Query all current positions for the SKU and their Inventory-owned warehouse state.
3. Calculate `is_in_stock = true` iff at least one active, non-`OUTAGE` warehouse has
   `available_to_sell > 0`.
4. If the state is new or the boolean changed, increment the per-SKU
    `availability_revision` and prepare a full snapshot fact.
5. Publish/export the fact only if Search has registered that its Product-owned SKU
   document exists. Without readiness, retain the latest state as pending; never fail or
   delay the authoritative command.

A warehouse active/outage change durably scans distinct SKU IDs at that warehouse with
checkpoint, backpressure, and idempotent retry. Each SKU is recomputed against all
warehouses; Inventory never assumes that one warehouse outage makes the SKU globally
unavailable. Only boolean transitions produce normal public events.

Product Detail BFF does not participate in this event workflow. It calls the
Availability Read API with a bounded SKU batch after Product resolves catalog-active
SKUs. The API reads `SkuAvailabilityState` without a write: a clean, completed state
maps to `IN_STOCK` or `OUT_OF_STOCK`; a missing or dirty state maps to `UNKNOWN`. Search
readiness does not gate this API, and a BFF read never creates a publication task.

After Search successfully creates a catalog SKU document, it registers readiness with
`catalog_revision`. Registration is idempotent and atomically persists a publication task
for the current availability snapshot before returning success, including when earlier
stock changes happened before readiness.
Search revokes readiness after tombstone and before physical purge.

## 5. Transition ownership

| Transition / fact | Sole actor | Inventory responsibility |
|---|---|---|
| Preview / Reserve / Commit / release | Order Temporal Saga (Preview read by Checkout) | Validate, transition atomically, retain command result. |
| Payment decision and timeout | Order | Never infer payment or auto-expire. |
| Physical dispatch, receipt, inspection, disposition, adjustment | `ADMIN` | Validate authority, physical guards, stock movement, audit. |
| Warehouse active/outage/priority | `ADMIN` | Own master state; use it only for new allocation. |
| Derive per-SKU `is_in_stock` | Inventory availability projector | Recompute asynchronously and publish only after Search document readiness. |
| Serve PDP advisory availability | Inventory Availability Read API | Return a bounded per-SKU state to Product Detail BFF; return `UNKNOWN` when state is missing/dirty; never reserve or expose quantity. |
| Register/revoke SKU document readiness | Search | Register only after catalog write succeeds; revoke after tombstone and before purge. |
| Refund registration/close/incomplete close | Order Temporal Saga | Enforce immutable scope and state guards. |
| Shipment/delivery | Order | Consume Inventory dispatch fact; not stored as Inventory lifecycle. |

The exact transport contract is in [interfaces.md](interfaces.md); state and bucket semantics are in [domain.md](domain.md).
