---
title: ADR-003 Checkout Preview and Order Reserve boundary
status: accepted
date: 2026-09-20
supersedes: ADR-001
---

# ADR-003: Checkout Preview and Order Reserve boundary

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
