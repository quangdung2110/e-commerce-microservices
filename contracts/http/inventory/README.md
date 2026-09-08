# Inventory HTTP contracts

Inventory exposes four independently authorized HTTP surfaces. None is a public
customer stock-write API.

| Contract | Caller | Purpose |
|---|---|---|
| [Workflow v1](workflow/v1/openapi.yaml) | Checkout, Order Temporal Saga | Advisory allocation, reservation lifecycle, Refund Scope, recovery queries |
| [Storefront availability read v1](read/v1/openapi.yaml) | Product Detail BFF | Bounded advisory per-SKU availability read; no quantity, warehouse, hold, or reservation data |
| [ADMIN v1](admin/v1/openapi.yaml) | Authenticated `ADMIN` with MFA | Warehouse master data and physical stock operations |
| [Export v1](exports/v1/openapi.yaml) | Search Projection | Product-document readiness register/revoke plus fixed-watermark per-SKU availability snapshot/change feed |

All mutation results are durable. The `Idempotency-Key` is scoped by authenticated
caller namespace and retained with the canonical request fingerprint for at least
seven years. A retry must reuse the same key. Business rejection is distinct from a
retryable infrastructure failure.

The Storefront availability read is not a mutation: it requires only workload identity
and correlation ID. Its `UNKNOWN`/`503` outcome is advisory UI degradation; Reserve is
still the only availability correctness decision.

The specifications describe the target Release 1 contract and remain `proposed`
until provider and consumer contract tests satisfy [contract adoption](../../ADOPTION.md).
