# Inventory Workflow API v1

[OpenAPI](openapi.yaml) is the target contract for Checkout allocation preview and
Order Temporal Saga commands. Preview is advisory. Reserve is exact-plan and
whole-order atomic; Payment never calls Inventory directly.

Every mutation requires `Idempotency-Key`, `X-Correlation-ID`, and
`X-Causation-ID`. Expected outcomes such as `REJECT_ALL` and
`RESERVATION_EXPIRED` are durable `200` results. `503 INVENTORY_UNAVAILABLE` and
`503 INVENTORY_BUSY` mean no authoritative transition was returned and must be
retried with the same command ID.
