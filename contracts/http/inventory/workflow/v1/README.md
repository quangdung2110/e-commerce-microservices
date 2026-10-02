# Inventory Workflow API v1

[OpenAPI](openapi.yaml) is the target contract for Checkout allocation preview and
Order Temporal Saga commands. Preview is advisory. Reserve is exact-plan and
whole-order atomic; Payment never calls Inventory directly.

Every mutation requires `Idempotency-Key`, `X-Correlation-ID`, and
`X-Causation-ID`. Expected outcomes such as `REJECT_ALL` and
`RESERVATION_EXPIRED` are durable `200` results. `503 INVENTORY_UNAVAILABLE` and
`503 INVENTORY_BUSY` mean no authoritative transition was returned and must be
retried with the same command ID.

`recover-timed-out-payment` is a fenced late-payment recovery, not a second Reserve:
it can commit only the same root released for `RESERVATION_EXPIRED`, with its exact
original plan still entirely available and no dispatch. A guarded rejection moves no
stock and leaves Order to financial reconciliation.

Refund Scope is a selected set of full-quantity `order_line_id` values, not a
whole-order or partial-quantity mechanism. Inventory retains one scope identity per
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
