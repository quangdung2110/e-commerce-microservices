---
doc_id: search-adr-018
service: search
status: superseded
date: 2026-09-07
decision_owners:
  - Search Service team
supersedes: ADR-014-inventory-stock-snapshot-contract
superseded_by: ADR-019-inventory-owned-sku-availability
evidence:
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/http/inventory/exports/v1/openapi.yaml
---

# ADR-018 — Inventory position projection and export watermark

## Status

`Superseded` by [ADR-019](./ADR-019-inventory-owned-sku-availability.md). The text
below is retained only as the historical decision that exposed the missing reverse lookup.

## Context

Inventory's accepted Release 1 design owns stock per `(warehouse_id, sku_id)` and
warehouse allocation eligibility. A per-SKU quantity snapshot cannot represent outage
or activity changes without hiding Inventory's authoritative derivation rules.

## Decision

Search consumes stock-position and warehouse-availability snapshots, guards each
aggregate with its own revision, and derives only `is_in_stock`. Bootstrap creates a
fixed-watermark Inventory snapshot session, imports warehouse and position pages, then
applies changes whose durable export sequence is greater than the watermark.

## Consequences

Inventory does not publish `product_id` or a globally authoritative per-SKU quantity.
Search joins by opaque `sku_id`, deduplicates CloudEvents by `id`, and bootstraps again
after `410 REPLAY_WINDOW_EXPIRED`. Kafka offset is not the Inventory snapshot boundary.

## Verification

Tests cover duplicate/reordered facts, multiple warehouses for one SKU, warehouse
outage/recovery, snapshot plus change-feed handoff, and replay-window expiration.
