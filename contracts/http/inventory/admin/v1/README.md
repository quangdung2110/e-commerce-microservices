# Inventory ADMIN API v1

[OpenAPI](openapi.yaml) is the only supported contract for warehouse master data,
sellable receipts/adjustments, physical dispatch, return receipt, final inspection,
quarantine disposition, and append-only reversal.

The caller must be an authenticated `ADMIN` with MFA. Actor identity comes from the
authentication context, never the request body. There is no generic bucket, ledger,
reservation, or Refund Scope update endpoint.
