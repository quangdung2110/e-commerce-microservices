# Checkout HTTP v1

Canonical buyer contract: [`openapi.yaml`](openapi.yaml).

The API creates immutable, server-owned quotations and never accepts client-owned
quantity, price, discount, address snapshot, or Inventory allocation data. Preview
requires an `Idempotency-Key`; Submit uses `checkout_token` as its logical
idempotency identity. Recovery is available independently through
`GET /api/v1/checkout/submissions/{checkout_token}`.

All responses contain private checkout or Payment data and must use
`Cache-Control: private, no-store`. Checkout never stores Payment client secrets.
