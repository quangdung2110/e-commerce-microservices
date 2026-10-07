# Inventory Workflow API v1

[OpenAPI](openapi.yaml) is the target contract for Checkout allocation preview and
Order Temporal Saga commands. Preview is advisory. Reserve is exact-plan and
whole-order atomic; Payment never calls Inventory directly.

Reserve/Commit/Release/recovery results are durable HTTP receipts, with no corresponding
public reservation-result events. Persist the stable command ID and request before the
call; after timeout/crash query `GET /commands/{command_id}` or replay the exact ID/body.
Do not infer failure from timeout or an absent query result while a command may be in
flight. Retain durable recovery work after retry exhaustion. Command queries prove the
original outcome, while `GET /reservations/{order_id}` proves current state. Only physical
dispatch and selected-scope inspection completion arrive asynchronously from Inventory.
See [Inventory ADR-020](../../../../../docs/inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md).

Every mutation requires `Idempotency-Key`, `X-Correlation-ID`, and
`X-Causation-ID`. Expected domain outcomes such as `REJECT_ALL` are durable `200`
results. `503 INVENTORY_UNAVAILABLE` and `503 INVENTORY_BUSY` mean no authoritative
transition was returned and must be retried with the same command ID.

Preview is stateless and `allocation_plan_id` never expires; Reserve stores it but
revalidates only the submitted lines. Commit is accepted whenever the root is still
`RESERVED`; `expires_at` is advisory and Order alone owns the payment timeout. Release
is always whole-root with one immutable reason (`PAYMENT_FAILED`,
`RESERVATION_EXPIRED`, `BUYER_CANCELLED`, `ADMIN_CANCELLED`,
`PRE_PAYMENT_SAGA_FAILED`). There is no single-line committed release; committed stock
is released only by `release-committed-before-dispatch`. See
[Inventory ADR-021](../../../../../docs/inventory/decisions/ADR-021-order-integration-lifecycle-corrections.md).

`recover-timed-out-payment` is a fenced late-payment recovery, not a second Reserve:
it can commit only the same root released for `RESERVATION_EXPIRED`, with its exact
original plan still entirely available and no dispatch. A guarded rejection moves no
stock and leaves Order to financial reconciliation.

Refund Scope is a selected set of full-quantity, already `DISPATCHED`
`order_line_id` values, not a whole-order or partial-quantity mechanism. Inventory retains one scope identity per
order lifetime. The admin automatic-refund path uses the separate all-or-nothing
`release-committed-before-dispatch` command and never registers a return scope.

Order closes a Buyer `INSPECTION_COMPLETED` scope after its terminal refund result:
`close` reason `REFUNDED` for success, `REJECTED` for terminal payout failure/reversal
without a prior close intent. The latter is unrelated to inspection FAIL. Retain an
earlier frozen command/reason after reversal. An audited no-payout decision uses
`close-incomplete` only for `IN_PROGRESS` with missing expected units and every
received unit final-inspected; zero-receipt `REGISTERED` instead uses `cancel`.

The 30-day timer never auto-closes. Query/replay the same frozen command after an
uncertain result; durable 409 requires reconciliation, not endpoint fallback.
Matching HTTP command receipts prove closure; no public closure event is added.
Terminal scope identity remains lifetime unique and cannot reopen after reversal.
See [Order ADR-013](../../../../../docs/order/decisions/ADR-013-durable-refund-scope-finalization.md).
