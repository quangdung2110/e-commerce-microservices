# Inventory HTTP contracts

Inventory exposes three independently authorized HTTP surfaces. None is a public
customer stock-write API.

| Contract | Caller | Purpose |
|---|---|---|
| [Workflow v1](workflow/v1/openapi.yaml) | Checkout, Order Temporal Saga | Advisory allocation, reservation lifecycle, Refund Scope, recovery queries |
| [ADMIN v1](admin/v1/openapi.yaml) | Authenticated `ADMIN` with MFA | Warehouse master data and physical stock operations |
| [Export v1](exports/v1/openapi.yaml) | Search Projection | Fixed-watermark snapshot and ordered availability change feed |

All mutation results are durable. The `Idempotency-Key` is scoped by authenticated
caller namespace and retained with the canonical request fingerprint for at least
seven years. A retry must reuse the same key. Business rejection is distinct from a
retryable infrastructure failure.

The specifications describe the target Release 1 contract and remain `proposed`
until provider and consumer contract tests satisfy [contract adoption](../../ADOPTION.md).
