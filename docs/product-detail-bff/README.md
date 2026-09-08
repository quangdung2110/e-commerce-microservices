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

Product Detail BFF là read-composition layer cho trang chi tiết sản phẩm (PDP). Nó
trả catalog và giá từ Product, trạng thái khả dụng advisory theo SKU từ Inventory
Availability Read API, cùng rating aggregate và preview hữu hạn từ Review. BFF không
sở hữu catalog, tồn kho, Review domain, database nghiệp vụ hoặc Inventory projection.

## Boundary

| Năng lực | Owner | Vai trò của BFF |
|---|---|---|
| Nội dung Product, media, variant, giá công bố, catalog lifecycle | Product | Đọc qua internal buyer-read API |
| Quy tắc `is_in_stock`, quantity, reserve và quyết định bán cuối cùng | Inventory | Batch-read trạng thái advisory; không gửi command reserve |
| Review content, eligibility, moderation và rating aggregate | Review | Đọc summary/preview qua internal API; không lưu hay nhận command Review |
| Browse, search, facet, suggestion | Search | Không gọi trong PDP hot path |
| PDP HTTP response | Product Detail BFF | Ghép catalog, availability và Review enrichment theo policy storefront |

PDP không đưa stock về Product. Khi buyer checkout, Checkout vẫn phải gọi Inventory
Reserve; `IN_STOCK` trên PDP là UI state, không phải xác nhận có thể mua.

## Dependencies

| Direction | Service/System | Protocol | Purpose |
|---|---|---|---|
| Inbound | Storefront qua Gateway | HTTP | Đọc Product Detail theo slug |
| Outbound | Product | HTTP nội bộ mTLS | Catalog/price/variant buyer-read |
| Outbound | Inventory | HTTP nội bộ mTLS | Batch-read advisory availability cho các SKU active |
| Outbound | Review | HTTP nội bộ mTLS/service identity | Rating aggregate và review preview cho PDP |

Không có Kafka consumer, PostgreSQL read model, Inventory export client hoặc Search
dependency trong BFF. Cache process-local hoặc CDN, nếu được bật, chỉ là tối ưu ngắn
hạn và không phải source of truth.

## Invariants

- Product, Inventory và Review là các nguồn độc lập; không suy diễn global version
  hoặc global transaction từ response của chúng.
- BFF chỉ gọi một Inventory batch-read cho tất cả SKU catalog-active của PDP; không
  gọi per-SKU và không truy cập database Inventory.
- BFF chỉ map trạng thái `IN_STOCK`, `OUT_OF_STOCK` hoặc `UNKNOWN` do Inventory trả;
  không tính availability từ quantity hoặc cache dài hạn.
- Thiếu một SKU trong response, `UNKNOWN` từ Inventory, timeout hoặc `503` từ
  Inventory đều phải render SKU là `UNKNOWN`, không suy diễn `OUT_OF_STOCK`.
- BFF không gửi reserve/deduct/release và không dùng availability UI để xác nhận đơn.
- BFF không gọi Search để lấy catalog hay stock cho PDP.
- BFF không lưu Review data, không consume Review event và không nhận command Review.
- Review unavailable phải được biểu diễn bằng `review_section.status = UNAVAILABLE`,
  không được giả thành aggregate 0 hoặc làm mất phần catalog của PDP.

## Tài liệu chuẩn

| Tài liệu | Vai trò |
|---|---|
| [Architecture](./architecture.md) | Thành phần, request path và consistency model |
| [Domain](./domain.md) | Status availability và business rule hiển thị |
| [Data](./data.md) | Không có persistent read model; cache boundary |
| [Interfaces](./interfaces.md) | Public PDP API và internal dependencies |
| [Workflows](./workflows.md) | Read và degradation flow |
| [Operations](./operations.md) | Dependency SLO, circuit breaker và runbook |
| [Development](./development.md) | Contract/integration test và Definition of Done |
| [Decisions](./decisions/README.md) | Availability batch-read và Review read composition |

## Trạng thái rollout

PDP có thể public availability khi Inventory Availability Read API đã có contract test
với BFF, service identity cho `product-detail-bff` được cấp và Inventory chứng minh
read model của chính nó đáp ứng freshness SLO. Trước đó, BFF có thể rollout catalog và
Review enrichment, nhưng mọi SKU phải là `UNKNOWN` thay vì sử dụng event/export cũ.

Review enrichment có thể rollout độc lập. Trước khi Review internal PDP contract sẵn
sàng, BFF trả review section ở trạng thái `UNAVAILABLE`; trạng thái này không đồng
nghĩa Product chưa có review.
