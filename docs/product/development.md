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

[schema.sql](./schema.sql) là DDL review; migration Flyway thực tế phải nằm trong
`java/services/product-service/src/main/resources/db/migration` và luôn additive.
Không chạy trực tiếp DDL review vào môi trường dùng chung.

gRPC hiện nằm tại
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto).
Trước implementation phải tạo contract tương thích cho base/selling price, catalog
saleability và `catalog_revision`; không thêm lại field stock đã reserved. HTTP và event
cần OpenAPI/AsyncAPI/JSON Schema cùng breaking-change check trong CI.

## Test bắt buộc

| Loại | Bao phủ |
|---|---|
| Unit | Công thức markdown, clamp giá, state machine admin-only |
| Integration DB | Constraint, Product → SKU lock order, hard delete/tombstone, catalog revision |
| Kafka | Outbox/CDC, event replay, `source_revision` cũ/trùng, consumer idempotency |
| Contract | gRPC, Product event snapshot/revision, Promotion markdown snapshot |
| End-to-end | Cart đọc catalog; Checkout reserve Inventory; Search ghép catalog với stock |


## Debugging

Kiểm tra `product_outbox_events` để xác nhận business change và event cùng transaction;
kiểm tra PostgreSQL lock/transaction khi có contention; kiểm tra Kafka consumer lag/DLQ;
và kiểm tra Debezium connector cùng replication slot khi event không tới Search/Promotion.

Không xóa outbox thủ công, không drop replication slot và không thay đổi offset Kafka
trên môi trường dùng chung nếu chưa có runbook/approval vận hành.

## Definition of Done

- Build, lint và test pass theo CI.
- Schema migration, OpenAPI/AsyncAPI/proto được cập nhật khi interface thay đổi.
- `catalog_revision` và `source_revision` được test khi logic chạm event/projection.
- Compatibility và rollout/rollback được review.
- Tài liệu hiện hành và ADR (khi có quyết định khó đảo ngược) được cập nhật.
