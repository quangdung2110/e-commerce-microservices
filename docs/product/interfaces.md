---
doc_id: product-interfaces
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Interfaces

HTTP và event chưa có OpenAPI/AsyncAPI. Semantics dưới đây là contract hiện hành và
phải được chuyển thành machine-readable contract trước khi implementation rollout.

## HTTP

Admin API dùng `/api/v1/products`, `/api/v1/categories` và yêu cầu role `ADMIN` từ
Gateway. Internal API phục vụ Search dùng service authentication.

| Endpoint | Hành vi |
|---|---|
| `POST /products` | Tạo Product `DRAFT` cùng SKU/media, ghi outbox |
| `PUT /products/{id}` | Cập nhật Product, tăng `catalog_revision`, ghi outbox |
| `PATCH /products/{id}/status` | Admin activate/hide/show, không đọc Inventory |
| `DELETE /products/{id}` | Hard delete sau khi ghi `PRODUCT_DELETED` tombstone |
| `POST`/`PUT`/`DELETE /products/{id}/skus` | Quản lý SKU; không xóa SKU cuối cùng; ghi tombstone khi delete |
| `GET /internal/products/export` | Export ổn định cho Search full reindex |
| `GET /internal/products/{product_id}/snapshot` | Rebuild một Product document |

## gRPC catalog read

`GetSku` và `GetBatchSkus` trả: `sku_id`, `product_id`, product name, variant,
image reference, `base_price_vnd`, `selling_price_vnd`, catalog saleability,
`catalog_revision`, `updated_at`. Chúng không trả quantity, `OUT_OF_STOCK` hay stock
status. Nếu Product/SKU không catalog-active, response là unavailable; Cart có thể giữ
SKU hết stock và Checkout phải reserve tại Inventory.

Proto hiện có tại
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto)
đã reserve field tồn kho, nhưng chưa diễn đạt đủ base/selling price, catalog revision và
catalog saleability. Phải tạo version contract mới hoặc mở rộng tương thích trước khi
đưa schema này vào implementation.

## Events Product phát ra

Outbox + Debezium phát at-least-once. Mọi event catalog của Product và SKU dùng Kafka
key `product_id`, mang full snapshot và `catalog_revision`; consumer guard bằng revision
và dedupe `event_id`.

| Event | Nội dung/consumer |
|---|---|
| `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATUS_CHANGED` | Snapshot Product, SKU/media, giá công bố; Search |
| `SKU_CREATED`, `SKU_UPDATED` | Snapshot Product aggregate sau thay đổi SKU; Search |
| `PRODUCT_DELETED`, `SKU_DELETED` | Tombstone; Search và Promotion; Inventory phải nhận hoặc có guard reserve tương đương |
| `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Snapshot/tombstone category; Search |

`aggregate_type = Product`, `aggregate_id = product_id`, `partition_key = product_id`
cho mọi Product/SKU event. Category event dùng Category/category ID. Event schema phải
có `event_id`, `event_type`, `timestamp`, `catalog_revision` khi thuộc Product aggregate
và payload snapshot đầy đủ.

## Events Product tiêu thụ

Product chỉ consume markdown snapshot từ Promotion:

| Event | Field bắt buộc | Hành vi |
|---|---|---|
| `PRODUCT_MARKDOWN_ACTIVATED` | `product_id`, rule, `source_revision`, thời hạn | Upsert projection, reprice SKU |
| `PRODUCT_MARKDOWN_UPDATED` | Như activate | Apply khi source revision mới hơn |
| `PRODUCT_MARKDOWN_DEACTIVATED` | `product_id`, rule, `source_revision` | Mark inactive, trả giá về base price |

Product không consume `INVENTORY_STOCK_CHANGED`. Search consume event này trực tiếp và
guard bằng sequence/version do Inventory cung cấp, tách biệt với `catalog_revision`.

## Compatibility

Breaking HTTP/gRPC/event change phải có version contract, rollout producer/consumer song
song và contract test. Không tái sử dụng số field protobuf đã loại bỏ; field quantity
cũ đã được reserve vĩnh viễn.
