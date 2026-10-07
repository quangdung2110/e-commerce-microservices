# ADR-018 — Selected-line Refund Scope and atomic pre-dispatch release

## Status

Accepted — 2026-09-15. Supersedes the Refund Scope part of ADR-013 and refines
the pre-dispatch release rule in ADR-012. ADR-013 remains historical evidence.

## Context

An immutable whole-order scope conflicts with Order's selected-line return policy.
Allowing a new scope after an old one becomes terminal also conflicts with Order's
single lifetime refund case. Admin needs a safe all-or-nothing stock release before
an automatic refund, rather than a loop of per-line releases racing physical dispatch.

## Decision

- A Refund Scope is an immutable selected set of `order_line_id` values. Every
  selected line must use its entire original reservation quantity; Inventory
  validates this against its immutable `ReservationLine`. The scope is neither a
  SKU set nor a partial-quantity mechanism.
- `RefundScope.order_id` is lifetime-unique. Replaying the same authenticated
  registration returns its existing result; another `refund_request_id`, including
  one after terminal closure, is rejected. A scope can therefore never be replaced
  by a later historical scope for the same order.
- Receipt, inspection, quarantine, multi-package accumulation and the one
  `all-items-inspection-completed` fact apply only to the selected expected lines.
  The fact fires after all expected quantity is final inspected, whether each unit
  passed or failed. Inventory never decides payout.
- Add `ReleaseCommittedOrderBeforeDispatch(order_id)`. In one relational
  transaction it locks the reservation root, every line and affected StockPosition,
  then releases all lines only if all are `COMMITTED` and none has physically
  dispatched. Otherwise it returns a durable conflict and moves no stock. It is
  restricted to Order's `ADMIN_FORCE_REFUND_BEFORE_DISPATCH` path.
- Existing single-line committed release remains an exception/recovery operation;
  it is not the implementation path for automatic admin refund.

## Consequences

Inventory preserves physical truth: PASS returns only inspected units to sellable,
FAIL remains quarantine, and an incomplete scope emits no completion fact. Order
uses the completion fact to call Payment even when failures exist. For an automatic
admin refund there is no Refund Scope, receipt or inspection because all stock is
proven not dispatched and is returned from committed to sellable atomically.

## Verification

Test exact full quantity validation, duplicate/replayed registration, second
historical registration rejection, multi-package mixed PASS/FAIL completion once,
and concurrent dispatch versus atomic order release: exactly one command wins and
the loser observes a fully consistent state with no partial movement.
