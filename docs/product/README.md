---
doc_id: product-overview
service: product
scope: current
status: implemented
owners:
  - Product Service team
evidence:
  - java/services/product-service
  - contracts/http/product/v1/openapi.yaml
  - contracts/events/product/v1/asyncapi.yaml
  - contracts/grpc/ecommerce/product/v1/product_service.proto
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
| Cart, quotation, order, payment | Cart/Checkout/Order | Đọc catalog/price ở Product; Checkout preview allocation và Order reserve ở Inventory |

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
| Inbound | Product Detail BFF | HTTP nội bộ mTLS | Buyer-read Product detail đã resolve category path, thuộc tính, trục biến thể và media giới hạn ([ADR-018](./decisions/ADR-018-resolved-buyer-product-detail-projection.md)); không có stock |
| Inbound | Search | HTTP nội bộ mTLS | Export Category/Product cho full reindex, kèm replay offsets theo topic; snapshot một Product để rebuild document |
| Inbound | Promotion | HTTP nội bộ mTLS | Chỉ `GET /internal/v1/products/{product_id}/snapshot`, để xác nhận Product tồn tại trước khi lưu markdown rule; không gọi được export |
| Inbound | Promotion | Kafka | ACTIVE full markdown hoặc INACTIVE tombstone, guard `source_revision` |
| Outbound | Search | Kafka | SKU/category snapshot (category kèm `facet_definitions`, [ADR-019](./decisions/ADR-019-category-facet-definitions.md)), tombstone và category-tree reindex control |
| Outbound | Promotion | Kafka | Product tombstone để persist terminal watermark và cleanup markdown rule |

Search consume stock availability snapshot/export trực tiếp từ Inventory. Product Detail
BFF gọi Inventory Availability Read API theo request sau khi có catalog. Product không
consume topic Inventory và không là đường trung chuyển availability cho UI.

Promotion sở hữu campaign schedule và ghi ACTIVE full payload/INACTIVE tombstone vào transactional
outbox tại logical boundary; Redis schedule + DB fallback bảo đảm bounded eventual
delivery và không kích hoạt sớm. Nếu consumer lỗi hoặc event vào DLQ, owner Promotion/operations
phải replay snapshot mới nhất; Product không có cronjob tự sửa giá quá hạn.

## Tài liệu chuẩn

| Tài liệu | Vai trò |
|---|---|
| [Data](./data.md) và [DDL](./schema/schema.sql) | Nguồn chuẩn cho ownership, schema và semantic contract |
| [Architecture](./architecture.md) | Thành phần và luồng xử lý hiện hành |
| [Domain](./domain.md) | Quy tắc nghiệp vụ và concurrency |
| [Interfaces](./interfaces.md), [OpenAPI](../../contracts/http/product/v1/openapi.yaml) và [AsyncAPI](../../contracts/events/product/v1/asyncapi.yaml) | HTTP, gRPC và event semantic; HTTP v1 và Events v1 `implemented`, gRPC v1 `proposed` vì proto còn là bản copy ([ADOPTION](../../contracts/ADOPTION.md)) |
| [Workflows](./workflows.md) | Luồng triển khai hiện hành |
| [Data](./data.md) | Data lifecycle, outbox và integrity |
| [Operations](./operations.md) | Vận hành, SLO và runbook |
| [Development](./development.md) | Hướng dẫn implementation/test |
| [Decisions](./decisions/README.md) | ADR lưu lịch sử quyết định; ADR-017 là boundary catalog/giá/Inventory, ADR-012 là outbox key, ADR-016 là markdown consumer, ADR-018 là buyer-read, ADR-019 là `facet_definitions` hiện hành |

## Trạng thái implementation

Đã implement tại [`java/services/product-service`](../../java/services/product-service): Java 21,
Spring Boot 3.3, PostgreSQL với Flyway `V1`–`V3`, gRPC `GetSku`/`GetBatchSkus`, transactional
outbox → Debezium, Markdown consumer, export nội bộ cho Search và buyer-read cho Product Detail
BFF. Cổng mặc định HTTP `8081` và gRPC `9091` (host `8082` và `9091` trong compose). Chi tiết
chạy và test ở [Development](./development.md), cấu hình ở [Operations](./operations.md).

Đã có test: unit (state machine, công thức giá, cursor, validator, allowlist theo route),
integration trên PostgreSQL thật (command, revision/ETag, tombstone, markdown, thumbnail, export
và replay offsets, gRPC, buyer-read validate với OpenAPI), contract test validate mọi CloudEvent
phát ra với JSON Schema, và test chống lệch bản copy proto.

Known gaps:

- Chưa ghi nhận smoke test với Debezium/Kafka thật cho Product outbox; connector nằm ở
  `src/main/resources/kafka` và chưa được compose đăng ký tự động.
- `facet_definitions` (ADR-019) chỉ mang nhãn nhóm; nhãn từng option facet ở Search vẫn dẫn xuất
  từ token vì descriptor không có option.
- `.proto` là bản copy trong service (ngoại lệ có chủ đích, có `ProtoCopyDriftTest`); xem
  [gRPC README](../../contracts/grpc/ecommerce/product/v1/README.md).
- Product gRPC không có nhãn biến thể (`variant_attributes` chỉ là mã → giá trị), nên Checkout
  gửi Order `variant_name` vắng mặt ([Checkout README](../checkout/README.md)).
- Chưa có consumer contract test từ Search, Promotion và Product Detail BFF.
