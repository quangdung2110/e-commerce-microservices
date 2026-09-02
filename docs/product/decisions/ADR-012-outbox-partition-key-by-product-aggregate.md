---
doc_id: product-adr-012
service: product
status: accepted
date: 2026-09-02
decision_owners:
  - Product Service team
supersedes:
  - ADR-002
evidence:
  - docs/product/data.md
  - contracts/events/product/v1/asyncapi.yaml
  - docs/product/decisions/ADR-010-catalog-event-revision-and-category-reindex.md
---

# ADR-012 — Outbox partition key theo Product aggregate

## Status

`Accepted`. Quyết định này thay thế ADR-002, giữ transactional outbox + Debezium CDC và
sửa rule partition key để khớp `catalog_revision` theo Product aggregate.

## Context

ADR-002 quy định event SKU dùng Kafka key `sku_id`, nhưng thiết kế catalog hiện hành dùng
một `catalog_revision` cho toàn Product aggregate. ADR-010, Data Design và AsyncAPI v1 đã
quy định Product/SKU event dùng key `product_id`, vì một mutation Product-level hoặc
markdown có thể phát snapshot cho nhiều SKU cùng revision.

Hai quy tắc đồng thời tạo tài liệu mâu thuẫn: key `sku_id` chỉ giữ thứ tự trong một SKU,
trong khi consumer phải guard toàn Product bằng `catalog_revision`.

## Decision

- Mọi business event vẫn được insert vào `product_outbox_events` cùng transaction catalog;
  Debezium là publisher duy nhất và đọc từ PostgreSQL WAL.
- `partition_key` của `sku-upserted`, `sku-deleted` và `product-deleted` là `product_id`.
  Mọi event này dùng `aggregate_type = Product`, `aggregate_id = product_id` và
  `sequence = catalog_revision`.
- Category event tiếp tục dùng `category_id` làm `partition_key`, aggregate ID và Kafka
  key; `sequence = category_revision`. `catalog-reindex-requested` dùng root category ID.
- Payload là CloudEvent structured JSON hoàn chỉnh; connector chỉ route topic, key và
  headers, không tự dựng business envelope. Product không publish Kafka trực tiếp.
- Đổi tên/kiểu `partition_key` hoặc mapping topic/key của connector là breaking change hạ
  tầng và phải rollout producer/consumer tương thích.

## Alternatives considered

- **Giữ key `sku_id`**: partition phân tán hơn nhưng thứ tự không khớp aggregate revision
  và snapshot fan-out markdown/Product-level.
- **Key theo event ID**: phân phối đều nhưng loại bỏ ordering theo aggregate hoàn toàn.
- **Polling publisher thay Debezium**: không giải quyết inconsistency key và thêm process/
  connection pool vào Product.

## Consequences

Consumer nhận event Product/SKU cùng aggregate theo cùng partition nên revision guard
dễ suy luận và tombstone không thể bị event cũ hồi sinh. Một Product có nhiều SKU hot có
thể tập trung vào một partition; đây là trade-off chấp nhận được vì mutation catalog không
có tần suất như Inventory và Product aggregate cần serial order.

## Migration impact

Cấu hình Debezium EventRouter và contract test phải dùng trực tiếp cột `partition_key`
với value `product_id` cho toàn bộ Product/SKU event. Consumer legacy dùng key `sku_id`
phải dual-read/migrate trước khi bị gỡ. Không đổi payload JSON Schema hay bảng outbox.

## Verification

- Integration test xác nhận mọi Product/SKU outbox row có `partition_key = product_id`.
- CDC test xác nhận Kafka key, CloudEvent `sequence` và payload khớp AsyncAPI v1.
- Replay test markdown/Product-level mutation nhiều SKU giữ ordering guard theo
  `catalog_revision` và không resurrect tombstone.
