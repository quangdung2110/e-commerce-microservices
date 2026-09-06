---
doc_id: product-detail-bff-data
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Data Design

## Ownership

`pdp_sku_availability` là read model có thể rebuild hoàn toàn từ Inventory export và
topic snapshot. Product không ghi vào bảng này; Inventory không truy cập bảng này.

BFF không persist rating aggregate, review content, author, media, report hoặc
`review_revision`. Review summary/preview chỉ sống trong phạm vi một request và source
of truth luôn là Review Service. Không được thêm foreign key hay join database sang
schema Review.

## Projection schema

| Column | Ý nghĩa |
|---|---|
| `sku_id` PK | Khóa join với variant Product |
| `product_id` | Guard chống drift và observability |
| `available_quantity` | Snapshot Inventory, không dùng để reserve |
| `stock_revision` | Guard đơn điệu per SKU |
| `observed_at` | Thời điểm Inventory quan sát state |
| `projected_at` | Thời điểm BFF apply snapshot |

Index bắt buộc là primary key `sku_id`. Không tạo foreign key sang Product vì hai
service không dùng chung database. Row của SKU Product đã xóa được giữ hoặc dọn theo
retention nội bộ; nó không thể làm PDP xuất hiện vì Product vẫn là authority.

## Apply và bootstrap

Event snapshot chỉ upsert khi `incoming.stock_revision > stored.stock_revision`.
Duplicate/cũ là no-op. Một row không có hoặc cũ hơn `availability_max_age` được render
`UNKNOWN`; threshold là runtime configuration và phải dựa trên SLO thực tế.

Bootstrap: export keyset từ Inventory → persist mọi item → replay topic từ offsets
Inventory cấp. Export/replay không được chạy bằng cách đọc toàn bộ topic từ đầu vì
retention Kafka là hữu hạn.
