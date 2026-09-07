---
doc_id: search-adr-015
service: search
status: accepted
date: 2026-09-04
decision_owners:
  - Search Service team
supersedes: ADR-004-per-source-ordering-guard
partially_superseded_by: ADR-019-inventory-owned-sku-availability
evidence:
  - docs/product/data.md
  - contracts/events/inventory/v1/asyncapi.yaml
  - contracts/events/review/v1/asyncapi.yaml
  - docs/search/data.md
---

# ADR-015 — Guard theo revision upstream đơn điệu

## Context

Guard theo từng source vẫn đúng, nhưng timestamp guard cũ cho Category/Inventory có thể
bỏ một state hợp lệ khi timestamp trùng hoặc clock khác nhau.

## Decision

> The Inventory-specific name below is historical and is superseded by ADR-019;
> Product, Category, and Review clauses remain accepted.

Catalog dùng `catalog_revision` per Product aggregate; Category dùng `category_revision`
per Category; Inventory dùng `availability_revision` per SKU; Review dùng `review_revision` per
product rating aggregate. Mọi guard dùng so sánh `>` chặt và source chỉ ghi field mình
sở hữu.

## Consequences

Không cần inbox/dedupe store cho duplicate event. Inventory refactor là dependency bắt
buộc trước khi availability path đạt trạng thái implemented.

## Verification

Contract fixture và integration test chứng minh event cũ/trùng không ghi đè state, còn
hai source khác nhau không làm mất field của nhau.
