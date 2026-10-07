# Checkout HTTP v1

Canonical buyer contract: [`openapi.yaml`](openapi.yaml). Adoption: `implemented`
(`java/services/checkout-service`, see
[Checkout ADR-013](../../../../docs/checkout/decisions/ADR-013-java-spring-webflux-runtime.md)).
Contract tests validate every response of the service against this document.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/checkout/preview` | Create the user's current immutable quotation |
| `GET` | `/api/v1/checkout/previews/{checkout_token}/voucher-options` | Voucher tray evaluated against that quotation |
| `POST` | `/api/v1/checkout/submit` | Submit one quotation exactly once |
| `GET` | `/api/v1/checkout/submissions/{checkout_token}` | Recover the durable submission result |

The API creates immutable, server-owned quotations and never accepts client-owned
quantity, price, discount, category facts, address snapshot, or Inventory allocation
data. Preview requires an `Idempotency-Key`; without `previous_checkout_token` it starts a
fresh quotation that supersedes the user's current `PREVIEWED` one, and it is refused with
`409 SUBMISSION_IN_PROGRESS` while a submission is still in flight
([Checkout ADR-016](../../../../docs/checkout/decisions/ADR-016-fresh-preview-and-in-flight-submission-guard.md)).
Submit uses `checkout_token` as its logical idempotency identity.

Voucher options are a read-only Backend for Frontend composition over the internal
Promotion `POST /internal/v1/promotion-voucher-options`; saving or claiming a voucher stays a
direct Promotion call
([Checkout ADR-017](../../../../docs/checkout/decisions/ADR-017-voucher-options-read-composition.md)).

Every error uses the `CheckoutProblem` catalog (`code`, `retryable`, `action`, optional
`status_url`, `changed_fields`, `sku_ids`, `rejected_lines`, `promotion_reason`,
`dropped_promotion`); the per-code HTTP status and client action table lives in
[docs/checkout/interfaces.md](../../../../docs/checkout/interfaces.md#error-catalog). `429` comes
from Gateway rate profiles.

There is no shipping fee: `final_amount = total_amount − total_discount` and must be at least
1 VND. All responses contain private checkout or Payment data and must use
`Cache-Control: private, no-store`. Checkout never stores Payment client secrets.
