---
title: ADR-012 Contract-aligned fail-closed orchestration
status: accepted
date: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
supersedes:
  - ADR-004
  - ADR-005
  - ADR-006
  - ADR-009
---

# ADR-012: Contract-aligned fail-closed orchestration

## Context

Thiết kế cũ tham chiếu Product/Inventory APIs không tồn tại, đọc Redis Cart trực tiếp, hỗ trợ degraded allocation/pricing và mô tả nhiều voucher trong khi contracts hiện hành yêu cầu whole-order allocation, một promotion scalar và Order allocation plan bắt buộc.

## Decision

- Checkout đọc Cart qua internal checkout-snapshot API, không đọc storage của Cart.
- Product `GetBatchSkus` là nguồn giá/catalog/category; `category_ids` đi xuyên
  Checkout item sang Order để Promotion Reserve dựng lại cùng trusted context.
- Inventory preview là whole-order có address và fail-closed. Không có stock degradation.
- Promotion nhận tối đa một `promotion_id`, `checkout_channel=CHECKOUT`, và `user_tier` chỉ khi có nguồn authoritative.
- Checkout revalidate revisions/expiry cần thiết trước submit; không chỉ dựa vào một hash không có contract owner.
- `user_ack_degraded_pricing` gửi Order luôn `false` như compatibility field; public Checkout không expose acknowledgement.
- Infrastructure failure không được chuyển thành business “no discount/no stock issue”.

## Consequences

- Dependency outage có thể làm giảm preview availability, đổi lại Order luôn nhận allocation/pricing có nguồn rõ ràng.
- Cart/Product/Promotion/Order contracts và Gateway/Mesh docs phải thay đổi đồng bộ.
- Voucher stacking và degraded mode chỉ có thể quay lại qua contract + ADR mới.

## Verification

- Consumer contract tests cho mọi mapping downstream.
- Repository scan không còn endpoint/key/degraded semantics cũ trong active documentation.
- E2E tests cover cart/price/promotion/allocation revision conflict.
