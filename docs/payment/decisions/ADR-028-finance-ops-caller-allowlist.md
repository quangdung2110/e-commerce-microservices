# ADR-028 — Caller allowlist for the Finance Ops routes

Status: Accepted — 2026-10-22. Refines [ADR-026](ADR-026-service-identity-allowlist-and-401-403.md), which left
`/internal/v1/payment/**` unchecked. Applies [contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md)
rules 1–3. Platform side of the same review: [mesh ADR-002](../../mesh/decisions/ADR-002-local-runtime-exposure-and-broker-auth.md).

## Context

The security review of 2026-10-22 (finding C2) found the Finance Ops API with no caller check at all.
`ServiceIdentityFilter` only covered the Order-facing routes, and `PaymentOperationsContractInterceptor` only
requires `X-Actor` to differ from `X-Approver`; both are free text. Anyone who reached port 8080 of Payment
(published on `0.0.0.0:8089` by docker-compose until mesh ADR-002) could replay webhooks, apply manual FSM
corrections, start reconciliation or lift the correction freeze by inventing two names. The mesh runtime
contract has no row for these paths, so under the mesh they are denied; outside it nothing stopped them.

## Decision

1. `ServiceIdentityFilter` checks `X-Service-Identity` per route group:

   | Group | Paths | Allowlist (property, env, default) |
   |---|---|---|
   | Order-facing | `/v1/payments/**` except `/v1/payments/webhook/**`, `/v1/refunds/**` | `payment.identity.allowed-callers`, `PAYMENT_ALLOWED_CALLERS`, `order-service` |
   | Finance Ops | `/internal/v1/payment/**` | `payment.identity.operations-callers`, `PAYMENT_OPERATIONS_CALLERS`, `finance-ops,accounting-ops` |
   | none | any other `/internal/**` | empty: every caller is rejected |

   Missing or blank is `401 UNAUTHENTICATED`, a name outside the group's list is `403 FORBIDDEN`, with the
   existing problem body (`code`, `retryable: false`, `correlation_id`, `Cache-Control: private, no-store`).
2. The defaults follow `finance-ops-operates-payment` in `contracts/catalog/interactions.yaml`. Without the
   property (code default) the list is empty and every caller is rejected.
3. The filter runs before the maker-checker interceptor, so an unauthenticated request never reaches the
   header validation, the idempotency store or a use case.
4. The decision uses the decoded path without `;param`, like ADR-026.
5. The internal OpenAPI (v1.1.0) declares `X-Service-Identity` (`finance-ops`, `accounting-ops`) and
   `401`/`403` on all nine operations.

## Alternatives considered

- **A separate management port that is never published.** Not chosen now: it needs a second connector in
  Spring Boot, a second Service port and topology entry, and new mesh rows; mesh ADR-002 already keeps 8089 on
  loopback in docker-compose and ClusterIP in Kubernetes. Recorded as an option if Finance Ops gets a runtime.
- **A new `payment-ops` identity.** Not chosen: the catalog already names `finance-ops` and `accounting-ops`
  as the consumers, and Order uses the same names for its reconciliation routes.
- **Rely on the mesh.** Not chosen: docker-compose has no mesh, and contracts ADR-004 requires the second layer.

## Consequences

- A caller that forgot the header now gets `401` instead of reaching the maker-checker check.
- `order-service` cannot call the Finance Ops routes (`403`).
- The header is still self-declared. Inside the Compose network a workload can still claim `finance-ops`; the
  real control remains the mesh `AuthorizationPolicy`, and the mesh contract still needs rows for these routes
  before Finance Ops gets a runtime (open item in mesh ADR-002).

## Migration impact

Code: `ServiceIdentityFilter`. Config: `application.yml` (`payment.identity.operations-callers`). Contract:
`contracts/http/payment/internal/v1/openapi.yaml` 1.0.0 → 1.1.0. No data migration.

## Verification

`ServiceIdentityFilterTest` (operations: missing, blank, wrong caller, allowed, encoded and `;param` paths,
cross-group callers, other internal paths, empty lists) and `PaymentOperationsControllerTest`
(`401` with no use-case interaction, `403` for `order-service`, existing cases with `finance-ops`). On the running
stack, probes `identity-payment-ops-missing` (`401`) and `identity-payment-ops-foreign` (`403`) in
`contracts/tools/provider-probes.yaml` (`scripts/ci/verify-providers.sh`).
