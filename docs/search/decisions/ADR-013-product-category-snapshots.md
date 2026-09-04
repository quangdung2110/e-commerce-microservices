---
doc_id: search-adr-013
service: search
status: accepted
date: 2026-09-04
decision_owners:
  - Search Service team
supersedes: ADR-010-derived-category-tree
evidence:
  - docs/product/interfaces.md
  - contracts/events/product/v1/schemas/category-upserted.schema.json
---

# ADR-013 — Materialize category snapshot Product, reindex khi tree đổi

## Context

Product v1 phát category snapshot có `path`, `is_leaf` và `category_revision`; rename,
move hoặc đổi slug phát thêm control event yêu cầu Search reindex. ADR-010 cũ buộc
Search tự dựng tree và fan-out `_update_by_query`, mâu thuẫn với contract mới này.

## Decision

Search materialize `ancestor_ids`, `ancestor_slugs`, `level` và `is_leaf` từ snapshot
Category Product, guarded bằng `category_revision`. Khi nhận control event, Search
coalesce rồi export Category trước Product để full reindex/catch-up. Search không tự
dựng tree và không đồng bộ path xuống SKU bằng thao tác diện rộng.

## Consequences

Product vẫn là nguồn snapshot cấu trúc; write path category của Product bounded. Đổi
tree cần reindex thay vì update nhỏ, nên job phải observable và singleton.

## Verification

Contract test category CloudEvent; test category revision cũ, control-event coalescing,
category-first export và alias không đổi khi reindex thất bại.
