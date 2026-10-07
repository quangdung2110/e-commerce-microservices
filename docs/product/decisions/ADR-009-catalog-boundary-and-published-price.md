---
doc_id: product-adr-009
service: product
status: accepted
date: 2026-09-01
decision_owners:
  - Product Service team
supersedes:
  - ADR-003
  - ADR-005
  - ADR-008
evidence:
  - docs/product/data.md
---

# ADR-009 — Product là catalog SoT; Inventory không được phản chiếu vào Product

## Context

Thiết kế v1 đưa quantity Inventory vào `product_skus`, tự cập nhật cache/DB qua hai
pha và dùng nó để auto-hide Product. Điều này làm Product vừa sở hữu catalog vừa duy
trì một bản sao tồn kho trên đường đọc của Cart/Checkout, trong khi Inventory mới là
nơi reserve/deduct. Search nay được xác định là UI read model và có thể consume event
Inventory trực tiếp.

Thiết kế v1 cũng yêu cầu Product tính markdown nhưng để Search nhận gián tiếp mọi
field qua Product. Khi Search consume Promotion trực tiếp, hai nơi sẽ phải tự tính
giá từ rule, dễ lệch công thức và lịch hiệu lực.

## Decision

- Product sở hữu catalog, SKU, base price và **published selling price**.
- Inventory là SoT duy nhất của availability và reservation. Product không lưu hay
  trả quantity/stock status; trạng thái `HIDDEN` không bao giờ do tồn kho tự đổi.
- Promotion sở hữu markdown rule. Product chỉ giữ projection một markdown active theo
  Product để tính `selling_price_vnd`; rule phải scope Product, scheduled và không
  overlap.
- Search nhận catalog/giá từ Product và inventory UI state trực tiếp từ Inventory;
  Search không nhận markdown rule trực tiếp.
- Product giữ transactional outbox. Không có inbox table; markdown snapshot event có
  source revision và local projection bỏ event cũ/trùng.
- Product và SKU dùng hard delete. Product tombstone event cho Search và Promotion
  được ghi trong transaction trước khi row bị xóa.

## Consequences

Cart chỉ xác nhận catalog/giá ở Product; item hết tồn vẫn có thể nằm trong cart.
Checkout/Order phải reserve tại Inventory và là nơi duy nhất từ chối hết hàng. Search
có thêm consumer Inventory và cần guard theo source riêng. Product có một fan-out
reprice khi markdown hoặc base price đổi, nhưng chỉ trong phạm vi một Product.

Một outbox table và Debezium vẫn là dependency vận hành. Đây là chi phí nhỏ hơn việc
chấp nhận crash window làm Search/Promotion mất một catalog event.

## Alternatives considered

- Giữ inventory replica ở Product: thuận tiện cho một gRPC call nhưng tạo stale
  authority và worker/cache phức tạp.
- Search tự tính markdown từ Promotion: nhân bản pricing logic và schedule ở read
  model.
- Checkout gọi Promotion để quote trực tiếp từng SKU: làm Pricing thành dependency
  đồng bộ của mọi Cart/Checkout read và không đơn giản hơn projection local.
- Kafka direct publish sau DB commit: đơn giản code ngắn hạn nhưng mất event khi
  process chết giữa commit và produce.

## Verification

- Product schema không chứa quantity, reservation, stock timestamp hay `OUT_OF_STOCK`.
- Product gRPC v2 không có `available_quantity`.
- Search indexer consume `INVENTORY_STOCK_CHANGED` và không upsert từ event đó.
- Cart contract test cho phép add SKU catalog-active mà không gọi Inventory.
- Markdown replay/out-of-order test chứng minh `source_revision` không reprice ngược.
