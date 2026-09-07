---
doc_id: search-adr-014
service: search
status: superseded
date: 2026-09-04
decision_owners:
  - Search Service team
supersedes: ADR-009-stock-projection-ownership
superseded_by: ADR-018-inventory-position-projection-contract
evidence:
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/http/inventory/internal/v1/openapi.yaml
---

# ADR-014 — Stock projection theo revision và snapshot Inventory

## Context

Inventory vẫn là source of truth của availability, nhưng event legacy chỉ có publish
timestamp. Timestamp không là ordering guard đủ mạnh; cũng không có snapshot/export để
seed hay phục hồi stock sau retention.

## Decision

Search VNext chỉ coi stock projection là fully supported khi Inventory cấp event snapshot
per-SKU gồm `product_id`, `sku_id`, `available_quantity`, `stock_revision` tăng đơn điệu
và `observed_at`, cùng internal stock snapshot/export dùng cho bootstrap/recovery. Search
đặt `is_in_stock = available_quantity > 0`, không upsert từ stock và guard bằng revision.

## Consequences

Search không quyết định reserve hay saleability giao dịch. Contract v1 dùng topic riêng
`t.inventory.stock.events.v1`; export trả offsets để replay sau import. Trước producer
rollout, adapter legacy chỉ là compatibility mode và không được hứa recovery/ordering tuyệt đối.

## Verification

Test event trùng/cũ, hai mutation cùng timestamp nhưng revision khác, seed từ snapshot,
replay Kafka sau seed và stock event cho SKU tombstone.
