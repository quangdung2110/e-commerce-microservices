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
AsyncAPI/JSON Schema đề xuất nằm tại
[`contracts/events/product/v1/asyncapi.yaml`](../../contracts/events/product/v1/asyncapi.yaml).

## HTTP

Admin API dùng `/api/v1/products`, `/api/v1/categories`, `/api/v1/attributes` và yêu
cầu role `ADMIN` từ Gateway. Internal API phục vụ Search, Promotion và Product Detail BFF
dùng mTLS/service identity do service mesh thực thi theo principal
([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
header `X-Service-Identity` do caller tự đặt và allowlist `product.internal.reindex-clients`
trong Product chỉ là defense-in-depth. Canonical path là `/internal/v1/...`, không expose qua
Gateway.

| Endpoint | Hành vi |
|---|---|
| `GET`/`POST /products` và `GET`/`PUT`/`DELETE /products/{id}` | Liệt kê, tạo Product `DRAFT`, thay phần Product-level, hard delete/tombstone |
| `PATCH /products/{id}/status` | Admin activate/hide/show, không đọc Inventory |
| `POST /products/{id}/skus`, `PUT`/`DELETE /products/{id}/skus/{sku_id}` | Quản lý SKU; không xóa SKU cuối cùng; ghi tombstone khi delete |
| `POST /products/{id}/media`, `PUT`/`DELETE /products/{id}/media/{media_id}` | Quản lý media Product hoặc media gắn SKU thuộc Product; phát lại snapshot cho các SKU có thumbnail hiệu lực đổi |
| `GET`/`POST /categories`, `GET`/`PUT`/`DELETE /categories/{id}` | Quản lý category tree |
| `GET`/`PUT`/`DELETE /categories/{id}/attributes/{attribute_id}` | Quản lý mapping category–attribute |
| `GET`/`POST`/`PUT`/`DELETE /attributes` và `/attributes/{id}/options` | Quản lý attribute `TEXT`/`NUMBER`/`BOOLEAN`/`SELECT` và option |
| `GET /internal/v1/products/export` | Keyset export hữu hạn cho Search full reindex, kèm `replay_from_offsets` của `t.product.events` chụp trước scan |
| `GET /internal/v1/categories/export` | Keyset export category tree, gồm path/is_leaf dẫn xuất và `replay_from_offsets` của `t.category.events`; Search đọc trước Product export |
| `GET /internal/v1/products/{product_id}/snapshot` | Rebuild một Product document |
| `GET /internal/v1/product-details/{slug}` | Buyer-read Product detail cho Product Detail BFF; chỉ trả catalog-active Product, media, variant và giá công bố, không có stock |

Mọi response chứa SKU — admin API, export và snapshot — mang `thumbnail_storage_key`
đã resolve, cùng giá trị với `sku-upserted`. Nhờ vậy full reindex từ export và luồng
event cho ra cùng kết quả, và consumer không phải derive lại từ `media[]` (ADR-013).

Hai export nội bộ tạo session qua cursor: offsets được chụp trước khi scan trang đầu
và lặp lại không đổi ở mọi trang. Search import hết snapshot trước, sau đó seek
inclusive tới offsets tương ứng trong pha Catch-up; `catalog_revision` và
`category_revision` làm phần chồng lấn trở thành no-op. Không chụp được đủ partition
hoặc không lập được session nhất quán thì Product trả `503`; xem
[ADR-014](./decisions/ADR-014-catalog-snapshot-replay-offsets.md).

Tất cả mutation Product/SKU/media sau khi tạo yêu cầu `If-Match` theo ETag
`catalog_revision`, trả `412 PRODUCT_REVISION_MISMATCH` khi admin đang ghi trên
snapshot cũ. Điều này là concurrency contract HTTP bổ sung cho pessimistic lock trong
database; lock tránh lỗi transaction còn ETag tránh lost update giữa hai admin/worker.

Schema hiện hành không có idempotency store, vì vậy OpenAPI **không** hứa
`Idempotency-Key` cho command admin. `POST /products` đặc biệt không idempotent; caller
gặp timeout phải tra cứu Product theo slug/ID trước khi retry. Nếu cần retry-safe command
trong tương lai phải thêm persistence idempotency và version contract/rollout tương ứng.

## Internal buyer Product Detail read

Product Detail BFF gọi `GET /internal/v1/product-details/{slug}` qua mTLS/service
identity. Response là snapshot Product và toàn bộ SKU catalog-active: nội dung, category,
media đã resolve, variant attribute, `base_price_vnd`, `selling_price_vnd`,
`catalog_revision` và `updated_at`. Product `DRAFT`/`HIDDEN`, slug không tồn tại, hoặc
Product không còn SKU saleable trả `404` để không rò rỉ catalog không công bố.

Endpoint không trả `available_quantity`, `is_in_stock`, `OUT_OF_STOCK`, reservation,
stock revision hay stock timestamp. Product Detail BFF ghép availability advisory từ
Inventory Availability Read API trong cùng PDP request; endpoint này không gọi Inventory
hoặc Search.

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

Outbox + Debezium phát CloudEvents 1.0 structured JSON, at-least-once. `id` là outbox
`event_id`; Product/SKU dùng Kafka key `product_id` và `sequence = catalog_revision`.
Product không phát full aggregate vô hạn: mỗi SKU bị ảnh hưởng nhận một snapshot đầy đủ
SKU + Product context, cùng revision. Consumer dedupe `id` và chỉ apply revision lớn hơn
state/tombstone hiện có.

| Topic / event | Consumer | Hành vi |
|---|---|---|
| `t.product.events` / `sku-upserted` | Search | Snapshot SKU khi Product/SKU/media/lifecycle/markdown làm SKU bị ảnh hưởng, gồm `thumbnail_storage_key` đã resolve. |
| `t.product.events` / `sku-deleted` | Search; Inventory nếu không có reserve guard | Tombstone một SKU với revision kế tiếp. |
| `t.product.events` / `product-deleted` | Search, Promotion; Inventory nếu cần | Tombstone Product và toàn bộ `sku_ids`. |
| `t.category.events` / `category-upserted`, `category-deleted` | Search | Category snapshot/tombstone; `sequence = category_revision`; snapshot có path dẫn xuất. |
| `t.catalog.control.events` / `catalog-reindex-requested` | Search | Rename, đổi slug hoặc đổi parent Category yêu cầu Search coalesce rồi full reindex. |

Category event dùng Kafka key `category_id`; Category revision tăng với mutation category
hoặc mapping category–attribute. Attribute/option không có event độc lập ở v1 vì chưa có
consumer đã được xác nhận. Search HTTP v2 cần `facet_definitions` (`code`, `label`,
`type`) trong category snapshot/export để render facet ổn định; đây là extension
tương thích cần được thêm vào Product event/export trước khi Search bật v2 facet.

## Events Product tiêu thụ

Product chỉ consume `com.ecommerce.promotion.product-markdown-snapshot.v1` từ
`t.promotion.markdown.events`, key `product_id`, `sequence = source_revision`. Contract do
Promotion sở hữu tại
[`contracts/events/promotion/v1/asyncapi.yaml`](../../contracts/events/promotion/v1/asyncapi.yaml);
Product AsyncAPI chỉ tham chiếu message qua `$ref`. Snapshot
luôn mang `product_id`, `source_revision`, `state`. `ACTIVE` mang rule/công thức VND/thời
hạn đầy đủ; `INACTIVE` chỉ mang `inactive_reason` và không mang rule/discount/interval.
Product chỉ apply revision lớn hơn, reprice cùng transaction rồi phát SKU snapshot
revision mới.
`ACTIVE` được Promotion ghi qua outbox tại logical campaign boundary; snapshot có thể
đến trễ trong bounded recovery window nhưng không được làm rule có hiệu lực trước
`starts_at`. Snapshot đến muộn sau `ends_at`
được ghi nhận revision nhưng không được làm giá cũ có hiệu lực lại. Promotion phải phát
`INACTIVE` khi hết hạn; Product không có scheduled safety check. Khi consumer retry/DLQ
làm giá stale, Promotion/operations phải replay snapshot mới nhất theo cùng contract.

Nếu Product đã hard delete, markdown event đến muộn được ack no-op. Product tombstone
`com.ecommerce.catalog.product-deleted.v1` được Promotion persist thành terminal
catalog-revision watermark rồi invalidate dangling markdown rule; Promotion không phát
INACTIVE ngược lại Product đã xóa.

Product không consume stock event, Product status event của chính nó hay markdown theo
Category. Search consume stock snapshot v1 trực tiếp từ Inventory với guard source riêng.

## Compatibility

Breaking HTTP/gRPC/event change phải có version contract, rollout producer/consumer song
song và contract test. Không tái sử dụng số field protobuf đã loại bỏ; field quantity
cũ đã được reserve vĩnh viễn.
