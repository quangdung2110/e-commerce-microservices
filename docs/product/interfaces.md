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
| `GET /internal/v1/product-details/{slug}` | Buyer-read Product detail cho Product Detail BFF; projection đã resolve của Product catalog-active: category path, thuộc tính, trục biến thể, media giới hạn và giá công bố, không có stock |

Slug Product phải khớp `^[a-z0-9]+(?:-[a-z0-9]+)*$` và dài tối đa 180 (PRODUCT-BR-013);
admin API trả `400` khi sai, Product không tự chuẩn hóa. Product Detail BFF dùng cùng quy
tắc cho path PDP.

Mọi response chứa SKU — admin API, export, snapshot và buyer-read — mang `thumbnail_storage_key`
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
identity. Response là schema `BuyerProductDetail`, một projection buyer đã resolve
([ADR-018](./decisions/ADR-018-resolved-buyer-product-detail-projection.md)), không phải
schema `Product` của admin:

| Phần | Nội dung |
|---|---|
| Product | `product_id`, `name`, `slug`, `description`, `catalog_revision`, `updated_at` |
| `category` | Category lá kèm `path` từ category gốc tới chính nó, mỗi phần tử có `category_id`/`name`/`slug` |
| `attributes[]` | Mapping `is_variant=false` có giá trị: `code`, `name`, `value_type`, `value`, `value_label` (label option khi `SELECT`, còn lại `null`); chưa có đơn vị đo |
| `variant_axes[]` | Mapping `is_variant=true` có SKU `ACTIVE` dùng; `options[]` chỉ gồm giá trị đang dùng, `SELECT` sắp theo option `sort_order` rồi `code`, kiểu khác theo giá trị |
| `media[]` | Danh sách phẳng, tối đa 20 media cấp Product và 10 media mỗi SKU `ACTIVE`, lấy theo `sort_order` rồi `media_id`; chỉ `storage_key`, không có CDN URL |
| `skus[]` | Chỉ SKU `ACTIVE`: `sku_id`, `sku_code`, `base_price_vnd`, `selling_price_vnd`, `variant_attributes` (code), `thumbnail_storage_key` |

`attributes` và `variant_axes` sắp theo `name` bằng collation tiếng Việt, tie-break `code`.
Category và nhãn attribute/option phản ánh trạng thái hiện hành lúc đọc; chúng không thuộc
Product aggregate nên đổi chúng không tăng `catalog_revision`, và buyer-read không phát
`ETag`. Product `DRAFT`/`HIDDEN`, slug không tồn tại, hoặc Product không còn SKU saleable
trả `404` để không rò rỉ catalog không công bố.

Endpoint không trả `available_quantity`, `is_in_stock`, `OUT_OF_STOCK`, reservation,
stock revision hay stock timestamp. Product Detail BFF ghép availability advisory từ
Inventory Availability Read API trong cùng PDP request; endpoint này không gọi Inventory
hoặc Search.

## gRPC catalog read

`GetSku` và `GetBatchSkus` trả: `sku_id`, `product_id`, `product_name`,
`variant_attributes`, `image_url`, `base_price`, published selling `price`,
`category_ids` (tối đa 20 ID duy nhất), catalog saleability,
`catalog_revision`, `updated_at`. `category_ids` là Product-owned ancestry để Checkout
dựng trusted Promotion facts; consumer không tự suy diễn category rule. Dù tên là
`image_url`, field này mang storage key của ảnh đại diện (ảnh `IMAGE` đầu tiên của SKU,
fallback ảnh cấp Product, rỗng khi không có ảnh), không phải URL tuyệt đối; consumer cần
hiển thị (Cart, Checkout) dựng URL CDN từ key như Search và Product Detail BFF. Chúng không trả quantity, `OUT_OF_STOCK` hay stock
status. Nếu Product/SKU không catalog-active, response là unavailable; Cart có thể giữ
SKU hết stock; Checkout phải lấy allocation preview và Order mới reserve tại Inventory.

Proto hiện có tại
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto)
đã diễn đạt `price` (selling price), `base_price`, `catalog_revision`, `category_ids`
và catalog saleability; field tồn kho cũ vẫn được reserve vĩnh viễn.

## Events Product phát ra

Outbox + Debezium phát CloudEvents 1.0 structured JSON, at-least-once. `id` là outbox
`event_id`; Product/SKU dùng Kafka key `product_id` và `sequence = catalog_revision`.
Product không phát full aggregate vô hạn: mỗi SKU bị ảnh hưởng nhận một snapshot đầy đủ
SKU + Product context, cùng revision. Consumer dedupe `id` và chỉ apply revision lớn hơn
state/tombstone hiện có.

| Topic / event | Consumer | Hành vi |
|---|---|---|
| `t.product.events` / `sku-upserted` | Search | Snapshot SKU khi Product/SKU/media/lifecycle/markdown làm SKU bị ảnh hưởng, gồm `thumbnail_storage_key` đã resolve. |
| `t.product.events` / `sku-deleted` | Search | Tombstone một SKU với revision kế tiếp. Inventory không consume ([Inventory ADR-026](../inventory/decisions/ADR-026-no-product-lifecycle-dependency.md)). |
| `t.product.events` / `product-deleted` | Search, Promotion | Tombstone Product và toàn bộ `sku_ids`. Inventory không consume. |
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
