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

> **Inventory integration review required (2026-09-07):** the direct per-SKU
> `stock-snapshot.v1` and export described below were superseded by Inventory's accepted
> position/warehouse contract, which currently registers Search as its only availability
> projection consumer. Treat the BFF availability path as unresolved until a separate
> consumer/ownership decision is accepted; these notes do not grant broker or Export API access.

Product Detail BFF là read composition layer cho trang chi tiết sản phẩm (PDP). Nó
trả catalog và giá từ Product, trạng thái khả dụng theo SKU từ projection cục bộ của
Inventory, cùng rating aggregate và preview hữu hạn đọc từ Review. BFF không sở hữu
catalog, tồn kho hay Review domain và không quyết định reserve.

## Boundary

| Năng lực | Owner | Vai trò của BFF |
|---|---|---|
| Nội dung Product, media, variant, giá công bố, catalog lifecycle | Product | Đọc qua internal buyer-read API |
| `available_quantity`, reserve và quyết định bán cuối cùng | Inventory | Consume snapshot để hiển thị; không gửi command reserve |
| Review content, eligibility, moderation và rating aggregate | Review | Đọc summary/preview qua internal API; không lưu hay nhận command Review |
| Browse, search, facet, suggestion | Search | Không gọi trong PDP hot path |
| PDP HTTP response | Product Detail BFF | Ghép catalog, availability và Review enrichment theo policy storefront |

PDP không đưa `available_quantity` trở lại Product. Khi buyer checkout, Checkout vẫn
phải gọi Inventory Reserve; trạng thái trên PDP là eventual-consistent UI state.

## Dependencies

| Direction | Service/System | Protocol | Purpose |
|---|---|---|---|
| Inbound | Storefront qua Gateway | HTTP | Đọc Product Detail theo slug |
| Outbound | Product | HTTP nội bộ mTLS | Catalog/price/variant buyer-read |
| Inbound | Inventory | Kafka | `stock-snapshot.v1` để materialize availability |
| Outbound | Inventory | HTTP nội bộ mTLS | Export snapshot chỉ cho bootstrap/recovery |
| Outbound | Review | HTTP nội bộ mTLS/service identity | Rating aggregate và review preview cho PDP |
| Outbound | PostgreSQL | Database | `pdp_sku_availability` read model |

## Invariants

- Product, Inventory và Review là các nguồn độc lập; không suy diễn một global version
  hoặc global transaction từ snapshot của các nguồn này.
- Chỉ event có `stock_revision` lớn hơn được ghi vào projection availability.
- Một SKU chưa có snapshot hợp lệ là `UNKNOWN`, không được suy diễn là `IN_STOCK`.
- BFF không gọi Search để lấy catalog hay stock cho PDP và không gọi Inventory cho mỗi
  page view.
- BFF không lưu Review data, không consume Review event và không nhận command Review.
- Review unavailable phải được biểu diễn bằng `review_section.status = UNAVAILABLE`,
  không được giả thành aggregate 0 hoặc làm mất phần catalog của PDP.
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
| [Decisions](./decisions/README.md) | Quyết định availability projection và Review read composition |

## Trạng thái rollout

BFF không được public availability trước khi Inventory triển khai stock snapshot v1,
`stock_revision` và export API. Trong giai đoạn hiện tại, legacy
`INVENTORY_STOCK_CHANGED` không đủ ordering/recovery guarantee để làm source cho PDP.

Review enrichment có thể rollout độc lập. Trước khi Review internal PDP contract sẵn
sàng, BFF trả review section ở trạng thái `UNAVAILABLE`; trạng thái này không đồng nghĩa
Product chưa có review.
