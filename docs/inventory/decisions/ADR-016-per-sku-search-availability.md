# ADR-016 — Inventory-owned per-SKU availability for Search

## Status

Accepted — 2026-09-07. Supersedes ADR-007 and the Search-specific position/warehouse
projection decision in ADR-014. Search ADR-019 is the consumer-side counterpart.

## Context

Inventory owns stock per `(warehouse_id, sku_id)` and warehouse allocation eligibility.
The previous Search contract exposed stock-position and warehouse-availability facts and
required Search to reconstruct the relation in both directions. That duplicated an
Inventory rule, required additional Search indices, and made a warehouse status change a
cross-document fan-out in a service that does not own warehouse state.

Search intentionally has no separate Inventory index. An availability event arriving
before its Product-owned SKU document would therefore be lost permanently if Search
acknowledged it.

## Decision

Inventory owns a derived integration projection `SkuAvailabilityState`, keyed by opaque
`sku_id`:

```text
is_in_stock = exists StockPosition(
  sku_id = target,
  available_to_sell > 0,
  Warehouse.is_active = true,
  Warehouse.operational_state != OUTAGE
)
```

The value is discovery data only, never a promise that Reserve will succeed. Inventory
publishes `com.ecommerce.inventory.sku-availability.changed.v1`, keyed by `sku_id`, as a
full boolean snapshot with `observed_at` and a strictly increasing
`availability_revision` per SKU. It
publishes on initialization and when the boolean changes, not for every quantity change.
The CloudEvents `aggregaterevision` equals `data.availability_revision`; export `sequence`
remains a recovery cursor and is not an ordering guard.

Stock-affecting commands still commit authoritative per-warehouse state without waiting
for Search. They durably mark affected SKU IDs for an asynchronous projector. The
projector serializes per SKU, recomputes from the relational primary, updates
`SkuAvailabilityState`, and writes the public outbox fact atomically with a changed or
new state. A warehouse active/outage change creates a durable, checkpointed fan-out over
distinct SKU IDs at that warehouse.

### Mandatory document-readiness precondition

Inventory **must not publish or export** availability for a SKU until Search has
registered that the Product-owned SKU document exists. After a successful catalog write,
Search calls the idempotent readiness operation in the Inventory Search Projection API
with `sku_id` and `catalog_revision`. Inventory stores the greatest registration revision;
registration atomically stores a durable publication task for the current availability
snapshot, even when no stock mutation occurs later. The readiness call cannot return
success before both the registration and publication task are durable.

Search revokes readiness after tombstoning and before physical purge. A stale revocation
cannot remove a newer lifecycle registration. Inventory keeps business mutations
independent of readiness: missing readiness delays only the Search-facing fact. Search
treats `document_missing` for an availability fact as a contract violation, does not ACK
it, and alerts; it never upserts a catalog document from Inventory data.

Bootstrap/recovery exports only registered per-SKU availability rows at one fixed
watermark, followed by the ordered change feed with `sequence > watermark`. Search writes
the snapshot directly into Product documents. A reindex target may defer a fact only
until its Product catch-up creates the corresponding document; unresolved missing targets
block cutover. Search rebuilds again after
`410 REPLAY_WINDOW_EXPIRED`; it creates no warehouse, position, or availability index.

## Consequences

- Inventory, not Search, owns the warehouse-aware availability rule and its revision.
- Search keeps one SKU document and one Inventory guard field; it does not store raw
  quantity or warehouse data.
- Inventory absorbs warehouse-change fan-out and must meet the Search freshness SLO with
  durable jobs, backpressure, and reconciliation.
- Readiness registration adds an asynchronous integration handshake, but never enters
  checkout, Reserve, or an Inventory command transaction.
- Product Detail BFF is not a consumer of this event/export contract. Its separate
  synchronous availability read is defined by ADR-017 and does not require Search
  readiness registration.

## Verification

Contract and integration tests cover: multiple warehouses for one SKU; last eligible
position going to zero and recovering; warehouse outage/recovery fan-out; concurrent
position mutations serialized per SKU; event suppression when the boolean is unchanged;
readiness-before-first-publication; registration after earlier stock mutations; stale
readiness revocation; `document_missing` not ACKed by Search; duplicate/reordered revision
guards; snapshot/change-feed handoff; and replay-window expiration.
