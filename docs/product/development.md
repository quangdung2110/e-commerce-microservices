---
doc_id: product-development
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Development Guide

## Dependencies

Runtime cần JDK 21, Maven, PostgreSQL, Kafka và Kafka Connect/Debezium khi test CDC.
Phiên bản và command chính xác phải lấy từ `pom.xml`, CI và compose hiện hành. Product
không cần Redis/RedisJSON cho thiết kế này.

## Schema và contract

[schema/schema.sql](./schema/schema.sql) là DDL review; migration Flyway thực tế phải nằm trong
`java/services/product-service/src/main/resources/db/migration` và luôn additive.
Không chạy trực tiếp DDL review vào môi trường dùng chung.

gRPC hiện nằm tại
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto).
Trước implementation phải tạo contract tương thích cho base/selling price, catalog
saleability và `catalog_revision`; không thêm lại field stock đã reserved. HTTP và event
cần OpenAPI/AsyncAPI/JSON Schema cùng breaking-change check trong CI. AsyncAPI Product
dùng CloudEvents structured JSON; payload outbox phải là CloudEvent hoàn chỉnh vì Debezium
EventRouter chỉ route/key/header, không tự dựng business envelope.

## Test bắt buộc

| Loại | Bao phủ |
|---|---|
| Unit | Công thức markdown, clamp giá, state machine admin-only |
| Integration DB | Constraint, Product → SKU lock order, hard delete/tombstone, catalog revision |
| Kafka | Outbox/CDC CloudEvents, catalog/category revision guard, event replay, `source_revision` cũ/trùng, ACTIVE→INACTIVE, missing Product, retry/DLQ và replay snapshot từ Promotion |
| Contract | gRPC, Product event snapshot/revision/effective thumbnail, Promotion ACTIVE full payload và INACTIVE tombstone |
| End-to-end | Cart đọc catalog; Checkout lấy Inventory allocation preview và Order reserve; Search ghép catalog với Inventory event projection, còn Product Detail BFF ghép catalog với Inventory batch-read |


## Debugging

Kiểm tra `product_outbox_events` để xác nhận business change và event cùng transaction;
kiểm tra PostgreSQL lock/transaction khi có contention; kiểm tra Kafka consumer lag/DLQ
và phối hợp Promotion replay snapshot mới nhất khi giá stale; kiểm tra Debezium connector
cùng replication slot khi event không tới Search/Promotion.

Không xóa outbox thủ công, không drop replication slot và không thay đổi offset Kafka
trên môi trường dùng chung nếu chưa có runbook/approval vận hành.

## Definition of Done

- Build, lint và test pass theo CI.
- Schema migration, OpenAPI/AsyncAPI/proto được cập nhật khi interface thay đổi.
- `catalog_revision` và `source_revision` được test khi logic chạm event/projection.
- Projection test bao phủ conditional CHECK, partial unique rule, inactive-first,
  ACTIVE→INACTIVE clear configuration/reset price và ACTIVE đến muộn sau `ends_at`.
- `category_revision`, tombstone revision và `CATALOG_REINDEX_REQUESTED` được test khi logic chạm category tree.
- Media test phải bao phủ ưu tiên ảnh SKU, fallback ảnh Product, loại `VIDEO`, giá trị `null` và fan-out đúng SKU khi ảnh Product đổi.
- Slug test (PRODUCT-BR-013): tạo/sửa Product với slug có chữ hoa, dấu gạch dưới, dấu tiếng
  Việt, gạch nối ở đầu/cuối hoặc liên tiếp, hay dài hơn 180 đều trả `400`. Migration
  `V3__product_slug_format.sql` không tự sửa slug: đổi slug phải đi qua admin API để tăng
  `catalog_revision` và phát `sku-upserted` cho Search. Khi còn slug sai, migration dừng trước
  khi đổi bất cứ thứ gì và liệt kê `product_id => slug` để admin sửa rồi chạy lại; khi sạch
  thì thêm `chk_products_slug_format`.
- Compatibility và rollout/rollback được review.
- Buyer Product Detail read test bao phủ `ACTIVE`/`HIDDEN`, media/variant/price và chứng minh response không có stock field.
  Theo ADR-018 còn phải chứng minh: category path gốc → lá; `value_label` chỉ có với `SELECT`;
  attribute/trục biến thể sắp theo tên (collation tiếng Việt, tie-break `code`); option chỉ gồm
  giá trị SKU `ACTIVE` dùng và đúng thứ tự; cắt 20/10 media đúng thứ tự, không có media của SKU
  `INACTIVE`; `thumbnail_storage_key` không đổi khi media bị cắt; response không có `ETag`.
- Tài liệu hiện hành và ADR (khi có quyết định khó đảo ngược) được cập nhật.
