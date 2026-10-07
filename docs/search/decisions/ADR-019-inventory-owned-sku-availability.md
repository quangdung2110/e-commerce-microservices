---
doc_id: search-adr-019
service: search
status: accepted
date: 2026-09-07
decision_owners:
  - Search Service team
  - Inventory Service team
supersedes: ADR-018-inventory-position-projection-contract
evidence:
  - docs/inventory/decisions/ADR-016-per-sku-search-availability.md
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/http/inventory/exports/v1/openapi.yaml
  - docs/search/schema/products.index.json
---

# ADR-019 — Inventory-owned per-SKU availability with document readiness

## Context

A SKU can have stock in multiple warehouses. Publishing position and warehouse facts
would force Search to store both projections and maintain a reverse lookup from warehouse
to every affected SKU. That conflicts with Search's Elasticsearch-only, minimal-index
boundary and duplicates Inventory's allocation eligibility rule.

There is also a creation race: Inventory must never publish a per-SKU fact before Product
has caused Search to create that SKU document. Search cannot safely upsert from an
Inventory event because Inventory does not own the catalog fields.

## Decision

Inventory owns and durably projects one availability aggregate per `sku_id`:

```text
is_in_stock = exists active, non-OUTAGE warehouse position
              whose available_to_sell > 0
```

It publishes `com.ecommerce.inventory.sku-availability.changed.v1`, keyed by `sku_id`,
with the complete `is_in_stock` value, `observed_at`, and monotonic
`availability_revision`. Search copies
that boolean into the existing Product-owned document and guards by revision. Search does
not store warehouse or stock-position facts and does not add an availability/internal
reverse-lookup index.

The following readiness protocol is a hard integration precondition, not best effort:

1. Product catalog upsert creates the Search SKU document.
2. Search calls `PUT /exports/inventory/v1/search-projection-readiness/{sku_id}` with
   the applied `catalog_revision` and commits the Product offset only after success.
   Success guarantees Inventory has durably stored both readiness and the task that
   publishes the current snapshot.
3. Inventory **must not publish or export** availability for that SKU before readiness is
   registered. Registration must cause the current snapshot to be published even when no
   later inventory mutation occurs.
4. After writing a catalog tombstone, Search revokes readiness. Revocation must complete
   before physical purge.

Therefore, an availability `document_missing_exception` is a contract violation. Search
records telemetry, does not ACK/commit the event, pages both owners, and never upserts.
Inventory readiness state is authoritative for this integration gate; it does not block
Inventory's reservation, warehouse, or stock command path.

Full rebuild creates Product documents first, then imports registered per-SKU availability
at a fixed Inventory watermark and applies the ordered change feed. A fact for a SKU that
was created after the Product snapshot is deferred until Product catch-up creates its
document; Search never upserts from that fact. Any unresolved missing target blocks
cutover. All availability remains in the `products` index.

## Consequences

- Inventory absorbs warehouse fan-out and owns the only implementation of the availability
  formula; warehouse status changes must durably mark every affected SKU for recompute.
- Search loses quantity detail and cannot explain which warehouse made a SKU available.
  That is intentional because buyer Search needs only a filter/display boolean.
- Catalog ingestion now depends on an idempotent internal Inventory call before committing
  its Kafka offset. Inventory outage delays readiness for new SKUs but does not corrupt an
  existing document or block Inventory's authoritative commands.
- No additional Search index, inbox, warehouse table, or reverse lookup is introduced.

## Alternatives considered

- **Search projects positions and warehouses.** Rejected because it requires extra data
  model/reverse lookup and duplicates Inventory rules.
- **Publish availability without readiness and ignore missing documents.** Rejected because
  a SKU with no later stock mutation can remain permanently stale.
- **Upsert a skeletal Search document from Inventory.** Rejected because Inventory does
  not own catalog identity, status, text, price, category, or media.

## Verification

Contract and integration tests must prove: Inventory never publishes/exports before
readiness; registration emits current state; duplicate/stale revisions are no-ops;
warehouse outage/recovery fans out every affected SKU; missing document is not ACKed or
upserted; revoke precedes purge; snapshot plus change feed converges in the existing
`products` index without creating another Search index.
