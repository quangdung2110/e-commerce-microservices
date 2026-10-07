---
doc_id: checkout-adr-012
service: checkout
status: accepted
date: 2026-09-20
decision_owners:
  - Checkout Service team
supersedes:
  - checkout-adr-004
  - checkout-adr-005
  - checkout-adr-006
  - checkout-adr-009
evidence:
  - docs/checkout/interfaces.md
  - docs/checkout/workflows.md
---

# ADR-012: Contract-aligned fail-closed orchestration

## Status

`Accepted` 2026-09-20. Thay thế ADR-004, ADR-005, ADR-006, ADR-009.

> **Ghi chú 2026-10-07 (đính chính):** Inventory allocation preview **không** nhận địa chỉ;
> request chỉ có `lines[{line_reference, sku_id, quantity}]` theo
> [Inventory Workflow OpenAPI](../../../contracts/http/inventory/workflow/v1/openapi.yaml).
> "Revalidate revisions/expiry" được cụ thể hoá thành: Cart `items_hash`, Product
> saleability/`price`/`category_ids`, kết quả Promotion calculation (Promotion không trả
> expiry). Thay cho nhánh tính lại voucher của ADR-006, Checkout relay
> `PROMOTION_RECALCULATION_NEEDED` của Order theo
> [ADR-015](./ADR-015-problem-catalog-view-model-and-order-outcomes.md).

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
