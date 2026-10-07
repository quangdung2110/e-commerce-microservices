# ADR-017 — Inventory-owned availability read API for Product Detail BFF

## Status

Accepted — 2026-09-09.

## Context

Product Detail BFF needs an availability state for every active SKU in a PDP response.
Inventory's accepted event/export contract is deliberately Search-only: it has document
readiness, snapshot/replay and index-recovery semantics that do not apply to BFF. Giving
BFF broker/export access would duplicate a stateful data pipeline in a composition layer
and expose a false dependency on Search's lifecycle.

Product is not allowed to replicate stock, and Search is neither PDP catalog authority
nor an availability fallback. A PDP status remains advisory because only Reserve can
make a stock decision.

## Decision

- Inventory exposes `POST /internal/inventory/v1/sku-availabilities:batch-get` only to
  the `product-detail-bff` service identity.
- The request contains 1–500 unique opaque SKU IDs. The response has exactly one item
  per requested SKU: `IN_STOCK`, `OUT_OF_STOCK`, or `UNKNOWN`, plus
  `availability_revision` and `evaluated_at` only for a known state.
- The API reads Inventory-owned `SkuAvailabilityState`; it has no transaction or side
  effect. If the state is missing or dirty/pending recompute it returns `UNKNOWN`; if
  the endpoint is unavailable it returns `503 INVENTORY_AVAILABILITY_UNAVAILABLE`.
- Inventory owns calculation, freshness, cache/read replica, capacity and recovery for
  this API. BFF owns its timeout/bulkhead/circuit breaker and maps timeout/503 to
  `UNKNOWN` without a hot-path retry loop.
- The surface exposes neither quantity, warehouse, allocation, reservation nor a
  promise that later Reserve succeeds. It is independent of Search readiness and is not
  backed by Search's index or event/export credentials.

## Alternatives considered

- **BFF event projection.** A valid future option only as a dedicated PDP read-model
  service with a formal event consumer, bootstrap/replay and SLO. It is disproportionate
  for the current single BFF and is not authorized by the Search contract.
- **Use Search for the PDP.** Rejected: Search does not own point-read catalog or have a
  PDP freshness/failure contract.
- **One Inventory call per SKU.** Rejected: creates N+1 fan-out and avoidable latency.
- **Product stock replica.** Rejected: violates Inventory ownership.

## Consequences and verification

Inventory has a synchronous read dependency from PDP traffic but can scale/cache it
inside its own boundary. A failed read does not block authoritative commands and only
causes client UI uncertainty. Before rollout, Inventory and Storefront must agree and
load-test latency/freshness budgets.

Contract tests prove BFF-only authorization, unique bounded input, exact result coverage,
no sensitive stock fields, `UNKNOWN` for dirty/missing state, and `503` behavior.
Integration tests prove a stock mutation can make the state dirty, recomputation yields
the correct boolean/revision, BFF degrades errors to `UNKNOWN`, and Reserve remains the
only correctness decision.
