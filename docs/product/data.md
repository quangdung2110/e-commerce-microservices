---
doc_id: product-data
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Data Design

## Ownership

Product sở hữu toàn bộ PostgreSQL schema của mình. Service khác không truy cập DB trực
tiếp. Product chỉ lưu catalog và markdown projection cần để công bố giá; không lưu bản
sao stock, reservation hay dữ liệu Inventory.

| Nhóm | Bảng |
|---|---|
| Catalog configuration | `categories`, `attributes`, `attribute_options`, `category_attributes` |
| Product aggregate | `products`, `product_skus`, `product_media` |
| Pricing projection | `product_markdown_projection` |
| Messaging | `product_outbox_events` |

Chi tiết cột, constraint và index nằm tại [Schema](./schema.md).

## Transaction boundaries

Mọi thay đổi Product aggregate ghi business rows và outbox trong cùng transaction.
Transaction lock Product trước SKU, tăng `catalog_revision` đúng một lần, rồi build
snapshot event từ dữ liệu đã mutate. Markdown consumer chỉ apply snapshot nếu
`source_revision` mới hơn projection hiện có.

Không có inbox/idempotency table. Inbound markdown event là state snapshot nên có thể
xử lý idempotent bằng `source_revision`; outbound event có `event_id` ổn định để consumer
dedupe. Không áp dụng mô hình này cho command tích lũy trong tương lai nếu chưa có
idempotency storage riêng.

## Outbox và lifecycle

Application insert `product_outbox_events` → PostgreSQL WAL → Debezium → Kafka. Outbox
không FK tới aggregate để tombstone còn được phát sau hard delete. Retention worker xóa
theo `created_at` sau khi vượt thời gian retention đã được vận hành xác nhận.

Hard delete Product cascade SKU/media/markdown projection. Command phải chụp `sku_ids`
và ghi tombstone trước khi delete. Search xóa document từ tombstone; stock event không
được tạo document mới để tránh zombie sau delete.

## Security và privacy

Product không chứa PII khách hàng. Outbox chứa snapshot catalog nên quyền đọc outbox
tương đương quyền đọc dữ liệu Product. Credential PostgreSQL/Kafka phải lấy từ secret
manager và không xuất hiện trong tài liệu, event hay log.
