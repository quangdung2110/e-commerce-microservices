---
doc_id: product-overview
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service

Product Service là source of truth cho catalog: Product, SKU, category, attribute,
media, giá niêm yết và giá bán công bố. Chỉ admin quản trị catalog.

## Boundary

| Năng lực | Owner | Vai trò của Product |
|---|---|---|
| Catalog, SKU, category, attribute, media | Product | Sở hữu và công bố snapshot catalog, gồm thumbnail hiệu lực theo SKU |
| Giá markdown rule, schedule, voucher | Promotion | Product giữ projection markdown active để tính giá công bố |
| Stock, availability, reservation, deduct | Inventory | Product không lưu và không trả dữ liệu này |
| Browse, filter, facet, ranking | Search | Nhận catalog/price từ Product, stock UI từ Inventory |
| Product Detail UI | Product Detail BFF | Đọc catalog/price từ Product và ghép stock UI ngoài Product |
| Cart, quotation, order, payment | Cart/Checkout/Order | Đọc catalog/price ở Product; Checkout reserve ở Inventory |

Không có Redis point-read cache, inventory projection, inventory worker, auto-hide theo
tồn kho, soft delete hay self-consumer trong Product. Markdown Consumer là background
workload nghiệp vụ duy nhất của Product; Product không chạy scheduled job.

## Invariants

- Inventory là source of truth duy nhất cho stock; chỉ Inventory quyết định reserve/deduct.
- Một Product luôn có ít nhất một SKU; không xóa SKU cuối cùng.
- Product chỉ thuộc category lá; category chỉ xóa khi không còn category con hay Product.
- Product dùng `DRAFT`, `ACTIVE`, `HIDDEN`; admin là nguồn duy nhất đổi trạng thái.
- Admin chỉ sửa `base_price_vnd`; Product tính và công bố `selling_price_vnd`.
- Mỗi thay đổi observable của Product aggregate tăng `catalog_revision` đúng một lần và
  ghi outbox trong cùng transaction.
- Markdown snapshot cũ/trùng không được ghi đè `source_revision` mới hơn.
- Product/SKU hard delete phải phát tombstone event.

## Dependencies

| Direction | Service | Protocol | Purpose |
|---|---|---|---|
| Inbound | Admin qua Gateway | HTTP | Quản trị catalog |
| Inbound | Cart, Checkout | gRPC | Catalog saleability và giá hiện hành |
| Inbound | Product Detail BFF | HTTP nội bộ mTLS | Buyer-read Product detail, không có stock |
| Inbound | Promotion | Kafka | Product-scope markdown state snapshot, guard `source_revision` |
| Outbound | Search | Kafka | SKU/category snapshot, tombstone và category-tree reindex control |
| Outbound | Promotion | Kafka | Product tombstone để dừng/cleanup markdown rule |
| Outbound | Search | HTTP nội bộ | Export Category/Product cho full reindex, kèm replay offsets theo topic |

Search và Product Detail BFF sẽ consume stock snapshot v1 trực tiếp từ Inventory sau
rollout contract. Product không consume topic Inventory và không là đường trung chuyển
availability cho UI.

Promotion sở hữu schedule markdown và phải phát snapshot `ACTIVE`/`INACTIVE` tại thời
điểm hiệu lực/kết thúc. Nếu consumer lỗi hoặc event vào DLQ, owner Promotion/operations
phải replay snapshot mới nhất; Product không có cronjob tự sửa giá quá hạn.

## Tài liệu chuẩn

| Tài liệu | Vai trò |
|---|---|
| [Data](./data.md) và [DDL](./schema/schema.sql) | Nguồn chuẩn cho ownership, schema và semantic contract |
| [Architecture](./architecture.md) | Thành phần và luồng xử lý hiện hành |
| [Domain](./domain.md) | Quy tắc nghiệp vụ và concurrency |
| [Interfaces](./interfaces.md), [OpenAPI](../../contracts/http/product/v1/openapi.yaml) và [AsyncAPI](../../contracts/events/product/v1/asyncapi.yaml) | HTTP, gRPC và event semantic; contract HTTP/Kafka v1 đang ở trạng thái proposed |
| [Workflows](./workflows.md) | Luồng triển khai hiện hành |
| [Data](./data.md) | Data lifecycle, outbox và integrity |
| [Operations](./operations.md) | Vận hành, SLO và runbook |
| [Development](./development.md) | Hướng dẫn implementation/test |
| [Decisions](./decisions/README.md) | ADR lưu lịch sử quyết định; ADR-009 là boundary, ADR-011/012 là worker và outbox key hiện hành |


## Trạng thái implementation

Thiết kế này chưa phải migration hay implementation. Cần tạo migration Flyway additive,
contract gRPC tương ứng; cập nhật Cart, Checkout, Promotion, Search
và Inventory; chạy reindex; sau đó mới gỡ hạ tầng cũ.
