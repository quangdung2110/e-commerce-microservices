---
doc_id: product-detail-bff-adr-003
service: product-detail-bff
status: accepted
date: 2026-09-20
decision_owners:
  - Storefront team
  - Inventory Service team
  - Checkout Service team
  - Order Service team
supersedes: null
amends:
  - ADR-001
evidence:
  - contracts/http/inventory/read/v1/openapi.yaml
  - contracts/http/inventory/workflow/v1/openapi.yaml
  - docs/mesh/runtime-contract.md
---

# ADR-003 — Ranh giới Checkout Preview và Order Reserve

## Status

`Accepted`. Không supersede ADR nào. Amend
[ADR-001](./ADR-001-availability-projection-over-live-query.md) ở điểm caller Reserve;
quyết định Inventory-owned batch availability read của ADR-001 giữ nguyên.

## Context

ADR-001 chọn Inventory-owned batch availability read đúng cho PDP nhưng gọi Checkout là Reserve caller. Contracts hiện hành tách advisory PDP read, Checkout allocation Preview và Order Reserve.

## Decision

- PDP BFF tiếp tục dùng Inventory batch availability read và degrade lỗi thành `UNKNOWN`.
- Checkout không dùng PDP state để quyết định bán; Checkout gọi whole-order allocation Preview.
- Order là caller duy nhất của Inventory Reserve và revalidate exact plan.

## Consequences

Giữ nguyên read-model architecture của ADR-001, đồng thời sửa ranh giới mutation để thống nhất Inventory/Order contracts.

## Verification

Catalog/mesh chỉ cấp PDP read cho BFF, Preview cho Checkout và Reserve cho Order.
