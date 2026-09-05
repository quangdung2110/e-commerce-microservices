---
doc_id: product-adr-014
service: product
status: accepted
date: 2026-09-05
decision_owners:
  - Product Service team
  - Search Service team
supersedes: null
evidence:
  - contracts/http/product/v1/openapi.yaml
  - contracts/events/product/v1/asyncapi.yaml
  - docs/search/decisions/ADR-017-product-catalog-snapshot-offset-contract.md
---

# ADR-014 — Catalog export trả replay offsets cho Search

## Status

`Accepted`.

## Context

Full reindex của Search cần ghép keyset export catalog với event phát sinh trong
lúc export. Trước đây Product chỉ trả dữ liệu export, còn Search tự đọc end offset
của Kafka để tạo catalog watermark. Cách này khiến boundary giữa snapshot Product
và log Kafka là suy đoán của consumer, khác với contract Inventory và Review, vốn
trả `replay_from_offsets` ngay từ export session.

Hai export của Product cũng là hai projection khác nhau: Category dùng
`t.category.events`; Product/SKU dùng `t.product.events`. Mỗi nguồn cần boundary
riêng để Search có thể replay sau khi import.

## Decision

`GET /internal/v1/categories/export` và `GET /internal/v1/products/export` tạo
export session. Trước khi scan trang đầu, Product chụp offset hiện tại của **mọi**
partition thuộc topic dữ liệu tương ứng và trả chúng dưới tên
`replay_from_offsets`.

- Category export trả offsets của `t.category.events`; Product export trả offsets
  của `t.product.events`.
- Offset là record đầu tiên Search phải `seek` và replay, theo semantics
  **inclusive**.
- Cursor ràng buộc các trang tiếp theo vào cùng export session; mọi trang của một
  session trả cùng tập offsets. Không tạo boundary mới ở trang sau hay khi retry
  một trang.
- Search phải import xong toàn bộ Category/Product snapshot vào index mới rồi mới
  replay event từ các offsets trên. `category_revision` và `catalog_revision` giữ
  event cũ/trùng thành no-op.
- Không chụp được đầy đủ offsets hoặc không giữ được export session nhất quán là
  lỗi `503`; Search không được cutover run đó.

`t.catalog.control.events` không là data stream để replay vào index. Search tiếp
tục consume control event; nếu nhận `catalog-reindex-requested` mới khi một run
đang build/catch-up, run đó bị supersede và không được cutover.

Decision này không tạo transaction phân tán giữa PostgreSQL và Kafka. Product vẫn
dùng transactional outbox/Debezium; catch-up phải quan sát publisher/consumer lag
trước cutover. Revision guard giải quyết phần chồng lấn giữa snapshot và event.

## Alternatives considered

- **Search tự chụp Kafka watermark.** Ít thay đổi Product nhưng Search phải biết
  topic/partition của upstream và không có contract ràng buộc watermark với export
  session.
- **Chạy consumer song song với export.** Có thể hội tụ nhưng làm mờ ranh giới
  Build/Catch-up, khó resume và không thống nhất với Inventory/Review.
- **Chỉ export, không replay.** Bỏ sót update/delete xảy ra sau khi item đã được
  scan.

## Consequences

Product export có Kafka là dependency: Kafka không sẵn sàng thì export reindex thất
bại dù database vẫn đọc được. Đổi lại, API nêu rõ replay boundary do service sở hữu
catalog cấp, Search không còn tự suy diễn watermark. Điều này thêm contract test cho
session phân trang và observability cho Kafka/outbox lag.

## Migration impact

OpenAPI Product v1 thêm field response bắt buộc cho hai endpoint nội bộ; chỉ Search
Service là caller nên rollout theo thứ tự Search chấp nhận field mới, Product phát
field, rồi Search bỏ cơ chế tự chụp watermark. Không đổi topic hay payload
CloudEvent.

## Verification

- Contract test: mọi trang của cùng export session có cùng offsets, đủ mọi
  partition và offset có semantics inclusive.
- Integration test: mutation Category/Product trong lúc export được replay, event
  cũ/trùng không ghi đè revision đã import và Kafka lỗi trả `503`.
- End-to-end test: Search không cutover run bị supersede bởi control event mới.
