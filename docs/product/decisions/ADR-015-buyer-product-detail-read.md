---
doc_id: product-adr-015
service: product
status: accepted
date: 2026-09-06
decision_owners:
  - Product Service team
  - Storefront team
supersedes: null
evidence:
  - docs/product-detail-bff
---

# ADR-015 — Buyer Product Detail read nội bộ cho BFF

## Status

`Accepted` (đã implement và Product Detail BFF đang gọi). Amended bởi [ADR-018](./ADR-018-resolved-buyer-product-detail-projection.md):
response là projection buyer đã resolve (category path, nhãn thuộc tính, trục biến thể,
media giới hạn) thay vì schema `Product` của admin, và không mang `ETag`. Endpoint, caller
và nguyên tắc không trả stock dưới đây giữ nguyên.

## Context

PDP cần toàn bộ catalog Product theo slug, trong khi gRPC hiện phục vụ Cart/Checkout
theo SKU và không đủ cấu trúc detail. Search không là PDP point-read authority, còn
Product không được giữ hoặc join Inventory stock theo ADR-009.

## Decision

Product thêm `GET /internal/v1/product-details/{slug}` dành cho Product Detail BFF qua
mTLS. Endpoint trả snapshot catalog/price/variant của Product `ACTIVE`, không có bất kỳ
field quantity, availability, reservation hay stock revision nào. Sau đó BFF ghép
availability advisory qua Inventory Availability Read API.

## Alternatives considered

- Expose Product trực tiếp cho browser: làm Product phải gánh storefront concern và
  không giải quyết availability composition.
- Dùng Search làm Product Detail API: Search không là catalog source of truth.
- Đưa stock replica vào Product: bị ADR-009 loại bỏ vì stale authority.

## Consequences và verification

Thêm một internal HTTP contract và dependency vận hành Product → BFF. Test phải chứng
minh Product `HIDDEN` không lộ, media/variant/price đúng snapshot và response không
chứa stock. BFF chỉ gọi Inventory sau khi nhận SKU catalog-active; Checkout lấy
allocation Preview và Order mới Reserve trực tiếp tại Inventory.
