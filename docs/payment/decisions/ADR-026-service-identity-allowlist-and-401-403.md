# ADR-026 — Configurable caller allowlist for the Order-facing routes, with 401 and 403

Status: Accepted — 2026-10-15. Applies [contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md) to Payment. Refines
ADR-001 to ADR-025 in nothing; it replaces a check that lived in the controllers. Decision 3 (Finance Ops routes
unchecked) is refined by [ADR-028](ADR-028-finance-ops-caller-allowlist.md).

## Context

The Order-facing controllers (`PaymentController`, `PaymentQueryController`) compare `X-Service-Identity` with the
literal `"order-service"` inside `validCommonHeaders` and answer `400 VALIDATION_FAILED` when it differs. That
differs from Inventory, Order, Auth and User in three ways: the allowed name is not configuration, a wrong or
missing caller is indistinguishable from a malformed request, and the same helper is copied into two controllers.
Callers read `400` as a request they built wrongly rather than as an identity problem.

## Decision

1. Add `ServiceIdentityFilter` for `/v1/payments/**` except `/v1/payments/webhook/**`, and for `/v1/refunds/**`.
   A missing or blank `X-Service-Identity` is `401 UNAUTHENTICATED`; a name outside
   `payment.identity.allowed-callers` (default `order-service`, env `PAYMENT_ALLOWED_CALLERS`) is
   `403 FORBIDDEN`. The body is `application/problem+json` with `code`, `retryable: false` and
   `correlation_id`; `Cache-Control: private, no-store`.
2. The decision uses the decoded path without `;param`. An empty allowlist rejects every caller.
3. The Stripe webhook is public and verified by signature, and `/internal/v1/payment/**` (Finance Ops) has its own
   callers; the filter does not touch either.
4. The controllers keep only a presence check for `X-Service-Identity` and `X-Correlation-Id`; the literal
   comparison is removed. `X-Correlation-Id` handling is unchanged.
5. The OpenAPI contract documents `401` and `403` on the six Order-facing operations.

## Alternatives considered

- **Keep `400`.** Rejected: contracts ADR-004 wants one outcome for an identity problem.
- **Move the check into a Spring interceptor.** Rejected: a servlet filter sees the decoded path before MVC routes
  it and matches how the other services do it.
- **Also cover `/internal/v1/payment/**`.** Rejected here: those callers (`finance-ops`, `accounting-ops`) are
  operator workloads without a runtime yet; their allowlist belongs with the maker-checker design.

## Consequences

Order classifies `401`/`403` like the former `400`: a durable rejection that points at configuration. Adding a
second caller of these routes is a configuration change. `PaymentControllerTest` and `PaymentQueryControllerTest`
now expect `403 FORBIDDEN` for a wrong caller.

## Migration impact

Code: `ServiceIdentityFilter` (new), `PaymentController`, `PaymentQueryController`. Config: `application.yml`
(`payment.identity.allowed-callers`). Contract: `contracts/http/payment/v1/openapi.yaml`.

## Verification

`ServiceIdentityFilterTest` (allowed, missing, blank, other caller, oversized correlation ID, encoded and
`;param` paths, webhook/actuator/ops untouched, empty allowlist) and the controller tests above.
