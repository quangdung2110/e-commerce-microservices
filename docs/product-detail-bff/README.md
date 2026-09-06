---
doc_id: product-detail-bff-overview
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
  - Product Service team
  - Inventory Service team
---

# Product Detail BFF

Product Detail BFF là read composition layer cho trang chi tiết sản phẩm (PDP). Nó
trả catalog và giá từ Product cùng trạng thái khả dụng theo SKU từ projection cục bộ
của Inventory. BFF không sở hữu catalog, không quyết định reserve và không là nguồn
chân lý của tồn kho.

## Boundary

| Năng lực | Owner | Vai trò của BFF |
|---|---|---|
| Nội dung Product, media, variant, giá công bố, catalog lifecycle | Product | Đọc qua internal buyer-read API |
| `available_quantity`, reserve và quyết định bán cuối cùng | Inventory | Consume snapshot để hiển thị; không gửi command reserve |
| Browse, search, facet, suggestion | Search | Không gọi trong PDP hot path |
| PDP HTTP response | Product Detail BFF | Ghép hai snapshot theo `sku_id` |

PDP không đưa `available_quantity` trở lại Product. Khi buyer checkout, Checkout vẫn
phải gọi Inventory Reserve; trạng thái trên PDP là eventual-consistent UI state.

## Dependencies

| Direction | Service/System | Protocol | Purpose |
|---|---|---|---|
| Inbound | Storefront qua Gateway | HTTP | Đọc Product Detail theo slug |
| Outbound | Product | HTTP nội bộ mTLS | Catalog/price/variant buyer-read |
| Inbound | Inventory | Kafka | `stock-snapshot.v1` để materialize availability |
| Outbound | Inventory | HTTP nội bộ mTLS | Export snapshot chỉ cho bootstrap/recovery |
| Outbound | PostgreSQL | Database | `pdp_sku_availability` read model |

## Invariants

- Product và Inventory là hai nguồn độc lập; không so sánh `catalog_revision` với
  `stock_revision` như một global version.
- Chỉ event có `stock_revision` lớn hơn được ghi vào projection availability.
- Một SKU chưa có snapshot hợp lệ là `UNKNOWN`, không được suy diễn là `IN_STOCK`.
- BFF không gọi Search để lấy catalog hay stock cho PDP và không gọi Inventory cho mỗi
  page view.
- `IN_STOCK` chỉ là gợi ý UI; Inventory Reserve là quyết định cuối cùng chống oversell.

## Tài liệu chuẩn

| Tài liệu | Vai trò |
|---|---|
| [Architecture](./architecture.md) | Thành phần và consistency model |
| [Domain](./domain.md) | Status availability và business rule hiển thị |
| [Data](./data.md) | Projection schema, revision guard và bootstrap |
| [Interfaces](./interfaces.md) | Public PDP API và internal dependencies |
| [Workflows](./workflows.md) | Read, consume và recovery flow |
| [Operations](./operations.md) | Freshness, lag và runbook |
| [Development](./development.md) | Contract/integration test và Definition of Done |
| [Decisions](./decisions/README.md) | Quyết định projection thay vì live Inventory query |

## Trạng thái rollout

BFF không được public availability trước khi Inventory triển khai stock snapshot v1,
`stock_revision` và export API. Trong giai đoạn hiện tại, legacy
`INVENTORY_STOCK_CHANGED` không đủ ordering/recovery guarantee để làm source cho PDP.
