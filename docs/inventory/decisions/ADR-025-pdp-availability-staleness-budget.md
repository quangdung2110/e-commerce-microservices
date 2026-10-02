# ADR-025 — Bounded staleness for the PDP availability read

## Status

Accepted — 2026-10-03. Refines the `UNKNOWN` rule of ADR-017. The endpoint, caller,
response fields and BFF degradation behavior of ADR-017 remain unchanged.

## Context

ADR-017 returned `UNKNOWN` whenever a SKU had pending dirty/recompute work. Every
Reserve, Release, receipt or adjustment marks its SKU dirty, so a SKU under steady
checkout traffic is almost always dirty. The best-selling products would therefore
show `UNKNOWN` on the PDP most of the time, although their last computed boolean is
seconds old and the projector is healthy. The PDP freshness budget was still "TBD".

## Decision

- The PDP availability freshness budget is **30 seconds**.
- `SkuAvailabilityDirty` keeps `first_marked_at`, the time of the oldest unprocessed
  mark for the SKU. Coalescing a new mark never resets it; a successful recomputation
  that covers all marks up to its start clears it.
- A warehouse `is_active`/`operational_state` fan-out that has not yet recomputed a
  SKU with a position at that warehouse counts as pending work for that SKU, aged from
  the fan-out job's creation.
- The batch read returns, per SKU:
  - `UNKNOWN` when no completed `SkuAvailabilityState` exists, or when its oldest
    pending work is older than 30 seconds;
  - otherwise the last completed `IN_STOCK`/`OUT_OF_STOCK` with its
    `availability_revision` and `evaluated_at`, even if newer work is pending.
- The result stays advisory UI data. Reserve remains the only stock decision, so a
  boolean up to about 30 seconds old cannot cause oversell.

## Alternatives considered

- **Keep `UNKNOWN` whenever dirty.** Correct but makes hot SKUs permanently unknown.
  Rejected.
- **60-second budget (equal to Search freshness).** Halves the alert sensitivity for a
  buyer-facing page with no benefit for a healthy projector. Rejected.
- **Compute live from StockPosition on every read.** Adds per-request multi-row reads
  on the hot read path and duplicates projector logic. Rejected.

## Consequences

- Hot SKUs show a known state while the projector keeps up; `UNKNOWN` becomes a real
  signal of projector lag or missing state.
- The `UNKNOWN` rate and pending-work age are the PDP freshness SLI.

## Migration impact

No implementation exists. Update [data.md](../data.md), [interfaces.md](../interfaces.md),
[workflows.md](../workflows.md), [architecture.md](../architecture.md),
[operations.md](../operations.md), [development.md](../development.md) and the Read
OpenAPI descriptions. Product Detail BFF behavior is unchanged.

## Verification

Tests prove: a SKU with pending work younger than 30 s returns its last state; older
than 30 s returns `UNKNOWN`; a missing state returns `UNKNOWN`; coalesced marks keep
the oldest age; an unfinished warehouse fan-out makes affected SKUs `UNKNOWN` after
30 s; the response never exposes quantity or warehouse.
