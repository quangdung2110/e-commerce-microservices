---
doc_id: cart-adr-012
service: cart
status: accepted
date: 2026-09-01
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/product/data.md
  - docs/cart/decisions/ADR-011-current-pull-read-and-redis-outbox-scope.md
---

# ADR-012 — Cart chỉ xác thực catalog, không xác thực tồn kho

## Context

Inventory Service là source of truth duy nhất của availability, reservation và
deduct. Schema v1 của Product phản chiếu quantity Inventory và Product gRPC trả
`available_quantity`; Cart dùng field đó để chặn thêm item. Điều này làm Cart dựa
vào một bản sao eventual-consistent của Inventory, không phải vào nguồn quyết định
giao dịch.

## Decision

Cart vẫn dùng pull-model Product gRPC khi thêm/đọc cart, nhưng chỉ xác thực:

- Product đang `ACTIVE` và SKU đang `ACTIVE` theo quyền catalog của admin;
- thông tin hiển thị và giá bán hiện hành (`selling_price_vnd`).

Cart không gọi Inventory, không lưu `available_quantity_snapshot`, không có domain
error `INSUFFICIENT_DISPLAY_STOCK` và không loại item chỉ vì hết hàng. Một SKU hết
tồn có thể nằm trong cart. Checkout/Order phải gọi Inventory để route và reserve;
chỉ reserve thành công mới xác nhận khả năng mua.

## Consequences

Cart giảm một dependency đồng bộ và không ra quyết định dựa trên inventory replica.
Đổi lại, user có thể chỉ biết hết hàng lúc Checkout. UI browse vẫn hiển thị tồn kho
từ Search, còn Checkout có thể trả lỗi reserve rõ ràng để user quay lại giỏ.

## Verification

- Product gRPC v1 đã được sửa không còn `available_quantity` hay `OUT_OF_STOCK`.
- Add/update cart chỉ gọi Product, không gọi Inventory.
- Cart Redis item không chứa stock quantity hay stock validation TTL.
- Contract test chứng minh Inventory reject ở Checkout không tạo cart mutation.
