# ADR-021 — Order-integration lifecycle corrections before implementation

## Status

Accepted — 2026-10-03. Refines ADR-012 (Commit deadline, release reasons, allocation
plan), supersedes the single-line committed release bullet of ADR-018, and removes the
`STOCK_DISCREPANCY` reservation release. ADR-012, ADR-018, ADR-019 and ADR-020 remain
active for every other rule.

## Context

A pre-implementation review compared the accepted Inventory target with Order's
checkout, cancellation, refund and late-payment workflows. Five mismatches would
force an implementer to guess:

1. **Commit deadline.** Inventory set `expires_at = Reserve accepted_at + 15 minutes`
   and rejected a new Commit after it. Order's 15-minute payment timer starts later
   (after Promotion reserve and Payment create), and Order calls Commit only *after*
   its financial watermark `PAID`, with activity retries. A Commit can therefore reach
   Inventory after `expires_at` while the root is still `RESERVED`. Order has no
   branch for that result, cannot roll back after `PAID`, and the fenced recovery
   requires a `RELEASED` root. The guard protects no stock, because Inventory never
   auto-releases a reservation.
2. **Release reasons.** `ReleaseReservation` accepted only `PAYMENT_FAILED`,
   `RESERVATION_EXPIRED` and `STOCK_DISCREPANCY`. Order also compensates Inventory
   for Buyer cancel, Admin cancel and a failed pre-payment step (for example Promotion
   reserve after Inventory reserve), so its rollback had no valid reason.
3. **Orphan commands.** `ReleaseCommittedAllocation` (one committed line) and the
   `STOCK_DISCREPANCY` release with `order_exception_id` have no Order caller and no
   Order exception model; their reason enums were never defined. They also create a
   mixed released/committed root that the lifecycle did not describe.
4. **Refund Scope eligibility.** Registration validated full original quantity but not
   that a selected line had physically left the warehouse.
5. **Allocation plan identity.** The documents did not state whether Inventory stores
   a preview plan, whether it expires, or what Reserve verifies about
   `allocation_plan_id` (also written `plan_id`).

## Decision

### 1. Commit is not deadline-gated

- `CommitPayment` is accepted for the whole root whenever the root is `RESERVED`,
  regardless of wall-clock time. Its only success outcome is `COMMITTED`.
  `RESERVATION_EXPIRED` is no longer a Commit outcome.
- Commit on a root that is not `RESERVED` (for example `RELEASED` after a timeout
  release) is a durable `409 RESERVATION_NOT_RESERVED` with no movement. A late
  payment for a timeout-released root uses only `RecoverTimedOutPayment` (ADR-019).
- `expires_at` stays in the reservation record and responses as an advisory
  payment-window reference (`accepted_at + 15 minutes`). Inventory uses it only for
  aging observation; the 60-minute orphan alert is measured from `accepted_at`.
  Order remains the sole owner of timeout and of the Commit/Release decision.

### 2. Complete release-reason set for a `RESERVED` root

`ReleaseReservation` accepts exactly these reasons; each releases the whole root:

| Inventory reason | Order cause (`cancel_reason`) |
|---|---|
| `PAYMENT_FAILED` | `PAYMENT_FAILED` |
| `RESERVATION_EXPIRED` | `PAYMENT_TIMEOUT` |
| `BUYER_CANCELLED` | `USER_CANCEL` |
| `ADMIN_CANCELLED` | `ADMIN_FORCE_CANCEL` |
| `PRE_PAYMENT_SAGA_FAILED` | `PRE_PAYMENT_SAGA_FAILED` |

The release reason is immutable on the root. Only `RESERVATION_EXPIRED` can later be
recovered by `RecoverTimedOutPayment`; every other reason is rejected by that command.

### 3. Remove single-line committed release and `STOCK_DISCREPANCY` from Release 1

- Remove `POST /internal/inventory/v1/reservations/{order_id}/lines/{order_line_id}/release`
  (`ReleaseCommittedAllocation`) and its reasons.
- Remove the `STOCK_DISCREPANCY` release reason and `order_exception_id`.
- The only committed-stock release is the all-or-nothing
  `ReleaseCommittedOrderBeforeDispatch` (ADR-018). A root is therefore never a mix of
  released and committed lines; `PARTIALLY_DISPATCHED` lines can only dispatch.
- A physical count discrepancy uses existing flows only: ADMIN negative-adjusts
  `available_to_sell`; if the shortage affects a `RESERVED` order, Order resolves it
  through its existing Admin pre-payment cancellation (`ADMIN_CANCELLED` release); if it
  affects a `COMMITTED` undispatched order, through Order's Admin pre-dispatch refund
  (`ReleaseCommittedOrderBeforeDispatch`); stock released to sellable can then be
  adjusted. A dispatched or otherwise unresolvable case is an operations incident with
  manual Order handling. Inventory never alters reserved/committed/quarantine units by
  adjustment.

### 4. Refund Scope lines must be dispatched

`RegisterRefundScope` additionally requires every selected `order_line_id` to be in
line state `DISPATCHED`. A selected line that is `RESERVED`, `COMMITTED` or `RELEASED`
is a durable `409 REFUND_SCOPE_LINE_NOT_DISPATCHED` with no scope created. Order's
Buyer policy (all lines delivered) already satisfies this; the guard is
defense-in-depth for the lifetime-unique scope.

### 5. Allocation plan is stateless

- Preview persists nothing. `allocation_plan_id` is an opaque Inventory-generated
  correlation identifier with no expiry and no server-side plan record.
- Reserve does not look up or validate `allocation_plan_id`. It stores it on the
  `OrderReservation` for audit/reconciliation and revalidates exactly the submitted
  `(order_line_id, sku_id, quantity, warehouse_id)` lines: warehouse active, not
  `OUTAGE`, and `available_to_sell >= quantity` at that warehouse. It never reranks.
- There is therefore no "expired plan" result; a stale plan surfaces only as
  `REJECT_ALL` from revalidation. The canonical name is `allocation_plan_id`.
- Inventory identifiers are opaque strings (`warehouse_id`, `sku_id` up to 128
  characters); Checkout v1 additionally limits `line_reference = sku_id` to 64.

## Alternatives considered

- **Order passes a payment deadline plus grace to Reserve.** Keeps a guard but leaves
  a boundary where a captured payment still cannot commit. Rejected.
- **Keep the Commit guard and add an Order branch (release, then recover).** Opens a
  window in which another order can take the released stock after capture and adds a
  second financial-reconciliation path. Rejected.
- **Define single-line release and an Order exception model.** Larger cross-service
  scope with no Release 1 business caller. Deferred; a future ADR can add it.
- **Stateful preview plan with TTL.** Adds storage and an expiry race but no
  correctness, because Reserve already revalidates under locks. Rejected.

## Consequences

- A captured payment always commits a still-`RESERVED` root; the 15-minute window is
  enforced in one place (Order).
- Order's rollback always has a valid reason, and late-payment recovery keeps its
  narrow `RESERVATION_EXPIRED` fence.
- The reservation lifecycle has no mixed release state, which simplifies locks,
  queries and the admin pre-dispatch release.
- Count discrepancies on promised stock need an Order Admin action before Inventory
  adjustment; there is no Inventory-side shortcut.

## Migration impact

Inventory implementation has not started and all affected contracts are `proposed`;
no deployed consumer migration is asserted. Update the Workflow OpenAPI (Commit
outcome, release reasons, removed endpoint and schemas, scope guard, plan description,
reservation query), Inventory target documents, Order interfaces/workflows/data, the
interaction catalog and this index. If any consumer had adopted the removed endpoint,
the 90-day breaking-change policy would apply.

## Verification

- Commit after `expires_at` on a `RESERVED` root returns `COMMITTED`; Commit on a
  `RELEASED` root returns durable `409` with no movement; replay returns the original.
- Each release reason releases the whole root and is stored immutably; recovery accepts
  only `RESERVATION_EXPIRED` and rejects the other four reasons with no movement.
- The removed endpoint and `STOCK_DISCREPANCY` do not appear in the active contract.
- Scope registration rejects any non-`DISPATCHED` selected line with no scope created.
- Reserve with an unknown `allocation_plan_id` but valid lines succeeds; a stale
  warehouse returns `REJECT_ALL`, never a reranked plan.

See [domain.md](../domain.md), [workflows.md](../workflows.md),
[interfaces.md](../interfaces.md), [data.md](../data.md),
[Workflow OpenAPI](../../../contracts/http/inventory/workflow/v1/openapi.yaml) and
[Order workflows](../../order/workflows.md).
