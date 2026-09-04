---
doc_id: search-adr-015
service: search
status: accepted
date: 2026-09-04
decision_owners:
  - Search Service team
supersedes: ADR-004-per-source-ordering-guard
evidence:
  - docs/product/data.md
  - docs/search/data.md
---

# ADR-015 — Guard theo revision upstream đơn điệu

## Context

Guard theo từng source vẫn đúng, nhưng timestamp guard cũ cho Category/Inventory có thể
bỏ một state hợp lệ khi timestamp trùng hoặc clock khác nhau.

## Decision

Catalog dùng `catalog_revision` per Product aggregate; Category dùng `category_revision`
per Category; Stock dùng `stock_revision` per SKU; Review giữ contract revision riêng khi
được xác minh. Mọi guard dùng so sánh `>` chặt và source chỉ ghi field mình sở hữu.

## Consequences

Không cần inbox/dedupe store cho duplicate event. Inventory refactor là dependency bắt
buộc trước khi stock path đạt trạng thái implemented.

## Verification

Contract fixture và integration test chứng minh event cũ/trùng không ghi đè state, còn
hai source khác nhau không làm mất field của nhau.
