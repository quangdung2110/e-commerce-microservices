---
title: ADR-017 Catalog price and Inventory orchestration boundary
status: accepted
date: 2026-09-20
supersedes: ADR-009
---

# ADR-017: Catalog price and Inventory orchestration boundary

## Context

ADR-009 đúng khi đưa stock ra khỏi Product nhưng gộp Checkout/Order thành caller Reserve. Inventory và Order contracts nay quy định Checkout chỉ Preview, Order mới Reserve.

## Decision

- Product là source of truth cho catalog lifecycle, published `price`, `base_price`, `catalog_revision` và `category_ids`.
- Product không consume/serve Inventory stock trên Cart/Checkout read path; field stock protobuf cũ tiếp tục reserved.
- Checkout gọi Product `GetBatchSkus`, sau đó gọi Inventory allocation Preview.
- Order là caller duy nhất của Inventory Reserve và revalidate exact allocation plan.
- Search/PDP availability vẫn là advisory read model, không thay Preview/Reserve.

## Consequences

Giữ pricing/catalog boundary của ADR-009, đồng thời loại ambiguity về stock mutation ownership.

## Verification

Proto không có stock field; catalog/mesh chỉ cho Checkout gọi Preview và Order gọi Reserve.
