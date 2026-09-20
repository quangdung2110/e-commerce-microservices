---
title: ADR-013 Checkout Preview and Order Reserve boundary
status: accepted
date: 2026-09-20
supersedes: ADR-012
---

# ADR-013: Checkout Preview and Order Reserve boundary

## Context

ADR-012 đúng khi loại Inventory khỏi Cart nhưng mô tả Checkout/Order như cùng caller của Reserve. Inventory workflow contract hiện tách rõ Checkout allocation Preview không tạo hold và Order Temporal Reserve nguyên tử.

## Decision

- Cart chỉ xác thực catalog và giữ quantity authoritative; không gọi Inventory.
- Checkout đọc Cart snapshot rồi gọi `POST /internal/inventory/v1/allocation-previews`.
- Preview `REJECT_ALL` làm Checkout từ chối quotation; `ALLOCATABLE` chỉ là advisory plan.
- Order là caller duy nhất của `POST /internal/inventory/v1/reservations` và là nơi revalidate plan/stock khi Reserve.

## Consequences

Giữ nguyên quyết định catalog-only của ADR-012 nhưng làm rõ ownership và không cho Checkout phát stock mutation.

## Verification

Mesh/catalog contract phải cho Checkout chỉ gọi Preview và cho Order gọi Reserve; contract tests từ chối principal sai.
