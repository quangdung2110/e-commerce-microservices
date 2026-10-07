# Inventory ADMIN API v1

[OpenAPI](openapi.yaml) is the only supported contract for warehouse master data,
sellable receipts/adjustments, physical dispatch, return receipt, final inspection,
quarantine disposition, append-only reversal, and the read-only operational queues
(warehouses, stock positions, movements, dispatch queue, inspection queue, quarantine
items, Refund Scope by order).

The surface is `/api/v1/admin/inventory/**` behind the API Gateway. The caller must be
an authenticated interactive user whose `X-User-Roles` contains `ADMIN`; actor identity
comes from the gateway-injected `X-User-Id`/`X-User-Session-Id`, never the request body.
There is no non-interactive ADMIN principal. MFA is mandatory for production and is a
release gate until an Auth/gateway decision forwards an MFA assertion
([Inventory ADR-024](../../../../../docs/inventory/decisions/ADR-024-admin-surface-and-error-catalog.md)).

Inventory generates `warehouse_id`; a warehouse carries a display `name`, and every update
sends `expected_revision` (`409 WAREHOUSE_CONFLICT` when stale). An unknown `warehouse_id`
in a command body is `409 WAREHOUSE_NOT_FOUND`; inspection, return-receipt-line and
disposition-line identifiers are globally unique
([Inventory ADR-027](../../../../../docs/inventory/decisions/ADR-027-pre-implementation-contract-corrections.md)).

Dispatch records an entire reservation line. `HOLD` is non-terminal; only `DISPOSE`
and `RETURN_TO_SUPPLIER` consume failed quantity. Every `409` carries a stable catalog
`code` and is replayed with the same body. There is no generic bucket, ledger,
reservation, or Refund Scope update endpoint.
