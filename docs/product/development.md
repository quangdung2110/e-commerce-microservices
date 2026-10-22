---
doc_id: product-development
service: product
scope: current
status: implemented
owners:
  - Product Service team
evidence:
  - java/services/product-service/pom.xml
---

# Product Service — Development Guide

## Dependencies

Java 21, Spring Boot 3.3.0, gRPC 1.63 / protobuf 3.25 (`grpc-spring` 3.1), PostgreSQL 16 với
Flyway, Kafka, và Kafka Connect/Debezium khi cần chạy CDC. Phiên bản chính xác ở
`java/services/product-service/pom.xml`. Product không cần Redis/RedisJSON.

Chạy local: dựng PostgreSQL (`localhost:15432`) và Kafka (`localhost:29092`, listener host chỉ bind 127.0.0.1) từ `docker-compose.yml`
ở repo root, rồi chạy service với profile `dev`; biến môi trường mẫu ở `.env.example`. Cổng HTTP
`8081`, gRPC `9091`. Máy không có sẵn JDK/Maven thì build bằng container
`maven:3.9-eclipse-temurin-21` với `-Dos.detected.classifier=linux-x86_64` (cần cho
`protobuf-maven-plugin`).

## Schema và contract

Flyway là nguồn thực thi schema: migration nằm trong
`java/services/product-service/src/main/resources/db/migration` (`V1`–`V3`) và luôn additive.
[schema/schema.sql](./schema/schema.sql) là DDL tổng hợp tương đương trạng thái cuối của chúng;
đổi schema thì thêm migration rồi cập nhật file này, không chạy file này vào môi trường dùng chung.

gRPC nguồn chuẩn ở
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto);
không thêm lại field stock đã reserved. `product-service/proto` là bản copy cho Docker build: sửa ở
`contracts/` rồi chép lại, `ProtoCopyDriftTest` fail nếu hai bản lệch. HTTP và event có
OpenAPI/AsyncAPI/JSON Schema ở `contracts/`; AsyncAPI Product dùng CloudEvents structured
JSON và payload outbox là CloudEvent hoàn chỉnh vì Debezium EventRouter chỉ route/key/header,
không tự dựng business envelope. Thêm field vào payload phải theo thứ tự consumer chấp nhận
trước, producer phát sau (xem `contracts/CHANGELOG.md`).

## Test bắt buộc

| Loại | Bao phủ |
|---|---|
| Unit | Công thức markdown, clamp giá, state machine admin-only, cursor, validator attribute, allowlist theo route (`TrustedIdentityFilterTest`, và `TrustedIdentityFilterPathTest` cho path mã hoá `%xx` / `;param`), mapping lỗi và `Retry-After` (`GlobalExceptionHandlerTest`) |
| Integration DB | Constraint, Product → SKU lock order, hard delete/tombstone, catalog revision, export và replay offsets, `facet_definitions` trong snapshot và export |
| Kafka | Outbox/CDC CloudEvents, catalog/category revision guard, event replay, `source_revision` cũ/trùng, ACTIVE→INACTIVE, missing Product, retry/DLQ và replay snapshot từ Promotion |
| Contract | gRPC, Product event snapshot/revision/effective thumbnail (`CatalogEventContractTest` validate JSON Schema), buyer-read validate với `BuyerProductDetail` trong OpenAPI, Promotion ACTIVE full payload và INACTIVE tombstone, bản copy proto (`ProtoCopyDriftTest`) |
| End-to-end | Cart đọc catalog; Checkout lấy Inventory allocation preview và Order reserve; Search ghép catalog với Inventory event projection, còn Product Detail BFF ghép catalog với Inventory batch-read |

Integration test tự dựng PostgreSQL bằng Testcontainers. Khi chạy Maven trong container (Testcontainers
không tới được Docker daemon), dựng một PostgreSQL riêng cho test và đặt `TEST_DB_URL`
(`TEST_DB_USERNAME`/`TEST_DB_PASSWORD` mặc định `postgres`). Mount **repo root** thay vì chỉ thư mục
service, nếu không `CatalogEventContractTest` và `ProtoCopyDriftTest` tự bỏ qua vì không thấy `contracts/`.


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
- Media test phải bao phủ ưu tiên ảnh SKU, fallback ảnh Product, loại `VIDEO`, giá trị `null`, và fan-out: media gắn SKU chỉ phát lại SKU đó, media cấp Product phát lại mọi SKU nhưng chỉ SKU đang fallback đổi thumbnail ([ADR-020](./decisions/ADR-020-media-fan-out-follows-snapshot-content.md)).
- `facet_definitions` test: snapshot rỗng khi chưa có mapping, sắp theo `code`, đổi theo mapping; đổi tên attribute đã map phát lại mọi category map nó (và không category khác); đổi tên attribute chưa map hoặc đổi option không phát event; export và event cho ra cùng giá trị.
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
