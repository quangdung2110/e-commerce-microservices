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
