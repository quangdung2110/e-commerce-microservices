# ADR-026 — Inventory has no Product lifecycle dependency

## Status

Accepted — 2026-10-03. Confirms the boundary in [README.md](../README.md) and resolves
a conflicting requirement in Product documentation.

## Context

Inventory accepts opaque `sku_id` and never calls Product. Product documentation and the
interaction catalog, however, required that "Inventory must consume Product tombstones
or enforce an equivalent reserve guard" to reject deleted SKUs. Both cannot hold, and an
implementer would not know whether Inventory needs a Product consumer.

## Decision

- Inventory consumes no Product event and keeps no Product lifecycle state. Reserve and
  Preview do not check whether a SKU is active or deleted.
- Preventing an inactive or deleted SKU from being sold belongs to Product and Checkout:
  Checkout validates catalog state at preview and revalidates Product at submit before
  Order reserves. Order reserves only the lines of an accepted checkout.
- A SKU deleted between Checkout submit and Reserve is an accepted narrow race; the
  order proceeds or is cancelled by Order's normal flows. Inventory never infers catalog
  state.
- Stock left for a deleted SKU remains physical truth. Search revokes readiness after the
  tombstone, so no availability is published for it; ADMIN removes or relocates the
  stock with normal audited adjustments.

## Alternatives considered

- **Inventory consumes `sku-deleted`/`product-deleted` and rejects Reserve.** Adds a
  consumer, inbox and tombstone table to the correctness path for a case Checkout
  already blocks. Rejected.

## Consequences

Inventory keeps a single synchronous dependency direction (callers to Inventory) and no
Product coupling. Product and the interaction catalog drop the Inventory consumer
requirement.

## Migration impact

Update Product [data.md](../../product/data.md) and
[interfaces.md](../../product/interfaces.md) and the interaction catalog note for
`product-publishes-catalog-events`.

## Verification

Inventory contract tests contain no Product dependency; Checkout tests prove an
inactive/deleted SKU is rejected at preview and submit.
