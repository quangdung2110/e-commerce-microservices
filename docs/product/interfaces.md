---
doc_id: product-interfaces
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Interfaces

HTTP đã có contract đề xuất tại
[`contracts/http/product/v1/openapi.yaml`](../../contracts/http/product/v1/openapi.yaml).
AsyncAPI cho outbox event vẫn chưa có; semantics event dưới đây phải được chuyển thành
machine-readable contract trước khi implementation rollout.

## HTTP

Admin API dùng `/api/v1/products`, `/api/v1/categories`, `/api/v1/attributes` và yêu
cầu role `ADMIN` từ Gateway. Internal API phục vụ Search dùng mTLS/service identity;
canonical path là `/internal/v1/...`, không expose qua Gateway.

| Endpoint | Hành vi |
|---|---|
| `GET`/`POST /products` và `GET`/`PUT`/`DELETE /products/{id}` | Liệt kê, tạo Product `DRAFT`, thay phần Product-level, hard delete/tombstone |
| `PATCH /products/{id}/status` | Admin activate/hide/show, không đọc Inventory |
| `POST /products/{id}/skus`, `PUT`/`DELETE /products/{id}/skus/{sku_id}` | Quản lý SKU; không xóa SKU cuối cùng; ghi tombstone khi delete |
| `POST /products/{id}/media`, `PUT`/`DELETE /products/{id}/media/{media_id}` | Quản lý media Product hoặc media gắn SKU thuộc Product |
| `GET`/`POST /categories`, `GET`/`PUT`/`DELETE /categories/{id}` | Quản lý category tree |
| `GET`/`PUT`/`DELETE /categories/{id}/attributes/{attribute_id}` | Quản lý mapping category–attribute |
| `GET`/`POST`/`PUT`/`DELETE /attributes` và `/attributes/{id}/options` | Quản lý attribute `TEXT`/`NUMBER`/`BOOLEAN`/`SELECT` và option |
| `GET /internal/v1/products/export` | Keyset export hữu hạn cho Search full reindex; consumer event chạy song song để nhận thay đổi đồng thời |
| `GET /internal/v1/products/{product_id}/snapshot` | Rebuild một Product document |

Tất cả mutation Product/SKU/media sau khi tạo yêu cầu `If-Match` theo ETag
`catalog_revision`, trả `412 PRODUCT_REVISION_MISMATCH` khi admin đang ghi trên
snapshot cũ. Điều này là concurrency contract HTTP bổ sung cho pessimistic lock trong
database; lock tránh lỗi transaction còn ETag tránh lost update giữa hai admin/worker.

Schema hiện hành không có idempotency store, vì vậy OpenAPI **không** hứa
`Idempotency-Key` cho command admin. `POST /products` đặc biệt không idempotent; caller
gặp timeout phải tra cứu Product theo slug/ID trước khi retry. Nếu cần retry-safe command
trong tương lai phải thêm persistence idempotency và version contract/rollout tương ứng.

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
