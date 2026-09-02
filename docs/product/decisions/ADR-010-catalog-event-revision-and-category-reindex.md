---
doc_id: product-adr-010
service: product
status: accepted
date: 2026-09-02
decision_owners:
  - Product Service team
supersedes:
  - ADR-006
evidence:
  - docs/product/schema/schema.sql
  - contracts/events/product/v1/asyncapi.yaml
---

# ADR-010 — Catalog event revision và full reindex khi đổi cây Category

## Status

`Accepted`.

## Context

`catalog_revision` đã order toàn Product aggregate, nhưng Category không có revision
bền vững. `updated_at` không đủ để Search quyết định event cũ/trùng, nhất là sau retry,
DLQ hoặc replay. Đồng thời rename, đổi slug hoặc move một Category làm path dẫn xuất của
cả subtree đổi, trong khi không Product/SKU nào bị mutate để phát catalog event mới.

ADR-006 dựa vào key SKU, `updated_at`, cache và lifecycle Inventory cũ. Các giả định đó
trái với boundary catalog-only được ADR-009 xác lập.

## Decision

- Thêm `categories.category_revision`, tăng đúng một lần cho mutation observable Category
  và mapping category–attribute. Category CloudEvent dùng revision này ở `sequence` và
  payload; Search guard theo revision, không theo timestamp.
- Product phát CloudEvents structured JSON qua outbox: snapshot theo SKU bị ảnh hưởng,
  SKU/Product tombstone, Category snapshot/tombstone và control event.
- Mọi event Product/SKU dùng Kafka key `product_id` và `catalog_revision`. Một mutation
  Product-level hoặc markdown fan-out một `sku-upserted` snapshot cho từng SKU ảnh hưởng;
  payload không chứa full aggregate không bị giới hạn kích thước.
- Rename, đổi slug hoặc đổi `parent_id` Category phát `catalog-reindex-requested` trong
  cùng transaction với category snapshot. Search coalesce yêu cầu rồi full reindex bằng
  export Category trước Product, đồng thời catch up catalog event và alias swap.
- Không fan-out subtree/Product trong transaction Category admin. Không có Attribute event
  riêng ở v1 khi chưa có consumer đã được xác nhận.
- Product chỉ consume một markdown state snapshot Product scope; `source_revision` là
  guard. Không consume Inventory hoặc Product event của chính nó.

## Alternatives considered

- Fan-out Category upsert cho mọi descendant/Product: tự động hơn nhưng unbounded outbox,
  lock và transaction khi admin move subtree lớn.
- Search rewrite prefix/category path theo event: event nhỏ hơn nhưng buộc Search giữ và
  update hàng loạt nhiều derived field, có ordering phức tạp giữa subtree overlap.
- Cấm move/rename Category đã dùng: đơn giản nhưng hạn chế quản trị catalog.

## Consequences

Search có thể tạm stale trong thời gian full reindex, nhưng correctness được phục hồi có
kiểm soát và Product write path luôn bounded. Search cần consumer cho
`t.catalog.control.events`, durable coalesce/job state và internal Category export.
`t.catalog.control.events` là thêm mới trong cấu hình Debezium/router và vận hành Kafka.

Tombstone Product/SKU phải mang revision kế tiếp để event cũ không resurrect document.
AsyncAPI v1 thay thế contract legacy SKU event của ADR-006; consumer cũ phải dual-read/
migrate trước khi gỡ event type cũ.

## Migration impact

Flyway additive thêm `category_revision` với default `1`; backfill Category hiện hữu ở
revision `1`. Producer/consumer CloudEvents triển khai song song với adapter legacy, sau
đó chuyển Search, Promotion và Inventory tombstone guard sang contract v1. Thêm endpoint
internal Category export và test full-reindex watermark/catch-up trước rollout.

## Verification

- Integration test Category revision, retry/replay và tombstone revision.
- Contract test JSON Schema/AsyncAPI cho toàn bộ CloudEvents.
- End-to-end test rename/move Category phát reindex request; Search export Category/Product,
  catch up events và atomically đổi alias mà không còn path cũ.
