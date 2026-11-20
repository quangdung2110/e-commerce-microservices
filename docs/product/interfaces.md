---
doc_id: product-interfaces
service: product
scope: current
status: implemented
owners:
  - Product Service team
---

# Product Service — Interfaces

HTTP contract: [`contracts/http/product/v1/openapi.yaml`](../../contracts/http/product/v1/openapi.yaml).
Event contract (AsyncAPI và JSON Schema):
[`contracts/events/product/v1/asyncapi.yaml`](../../contracts/events/product/v1/asyncapi.yaml).
Trạng thái adoption của từng contract ở [ADOPTION](../../contracts/ADOPTION.md).

## HTTP

Admin API dùng `/api/v1/products`, `/api/v1/categories`, `/api/v1/attributes` và yêu
cầu role `ADMIN` từ Gateway. Internal API phục vụ Search, Promotion và Product Detail BFF
dùng mTLS/service identity do service mesh thực thi theo principal
([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint));
header `X-Service-Identity` do caller tự đặt và Product chỉ kiểm lại như defense-in-depth.
Canonical path là `/internal/v1/...`, không expose qua Gateway.

Allowlist trong Product tách theo nhóm route; route internal chưa thuộc nhóm nào bị từ chối:

| Nhóm route | Cấu hình `product.internal.*` | Caller |
|---|---|---|
| `GET /internal/v1/products/export`, `GET /internal/v1/categories/export` | `reindex-clients` | `search-service` |
| `GET /internal/v1/products/{product_id}/snapshot` | `snapshot-clients` | `search-service`, `promotion-service` |
| `GET /internal/v1/product-details/{slug}` | `buyer-read-clients` | `product-detail-bff` |

Promotion chỉ có quyền ở hàng snapshot: nó validate một Product trước khi lưu markdown rule và
không được đọc toàn catalog (gồm cả Product `DRAFT`/`HIDDEN`) qua export. Thiếu header là `401`,
identity ngoài allowlist của nhóm là `403`.

| Endpoint | Hành vi |
|---|---|
| `GET`/`POST /products` và `GET`/`PUT`/`DELETE /products/{id}` | Liệt kê, tạo Product `DRAFT`, thay phần Product-level, hard delete/tombstone |
| `PATCH /products/{id}/status` | Admin activate/hide/show, không đọc Inventory |
| `POST /products/{id}/skus`, `PUT`/`DELETE /products/{id}/skus/{sku_id}` | Quản lý SKU; không xóa SKU cuối cùng; ghi tombstone khi delete |
| `POST /products/{id}/media`, `PUT`/`DELETE /products/{id}/media/{media_id}` | Quản lý media Product hoặc media gắn SKU thuộc Product; media gắn SKU phát lại snapshot của SKU đó, media cấp Product phát lại snapshot của mọi SKU vì `media[]` của mỗi snapshot gồm cả media cấp Product ([ADR-020](./decisions/ADR-020-media-fan-out-follows-snapshot-content.md)) |
| `GET`/`POST /categories`, `GET`/`PUT`/`DELETE /categories/{id}` | Quản lý category tree |
| `GET`/`PUT`/`DELETE /categories/{id}/attributes/{attribute_id}` | Quản lý mapping category–attribute |
| `GET`/`POST`/`PUT`/`DELETE /attributes` và `/attributes/{id}/options` | Quản lý attribute `TEXT`/`NUMBER`/`BOOLEAN`/`SELECT` và option |
| `GET /internal/v1/products/export` | Keyset export hữu hạn cho Search full reindex, kèm `replay_from_offsets` của `t.product.events` chụp trước scan |
| `GET /internal/v1/categories/export` | Keyset export category tree, gồm path/is_leaf/`facet_definitions` dẫn xuất và `replay_from_offsets` của `t.category.events`; Search đọc trước Product export |
| `GET /internal/v1/products/{product_id}/snapshot` | Search rebuild một Product document; Promotion xác nhận Product tồn tại trước khi lưu markdown rule |
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
`variant_attributes`, `variant_name`, `image_url`, `base_price`, published selling `price`,
`category_ids` (tối đa 20 ID duy nhất), catalog saleability,
`catalog_revision`, `updated_at`. `category_ids` là Product-owned ancestry để Checkout
dựng trusted Promotion facts: category của Product và mọi ancestor tới root, root trước; nếu
cây sâu hơn 20 cấp thì giữ 20 category gần Product nhất (là những category mà rule gọi tên).
Consumer không tự suy diễn category rule. Dù tên là
`image_url`, field này mang storage key của ảnh đại diện (ảnh `IMAGE` đầu tiên của SKU,
fallback ảnh cấp Product, rỗng khi không có ảnh), không phải URL tuyệt đối; consumer cần
hiển thị (Cart, Checkout) dựng URL CDN từ key như Search và Product Detail BFF. Chúng không trả quantity, `OUT_OF_STOCK` hay stock
status. Nếu Product/SKU không catalog-active, response là unavailable; Cart có thể giữ
SKU hết stock; Checkout phải lấy allocation preview và Order mới reserve tại Inventory.

`variant_name` là display text hiện tại: các cặp tên attribute/giá trị sắp theo code,
nối bằng ` · `; SELECT lấy label option, BOOLEAN Có/Không, kiểu khác scalar text.
Attribute không còn định nghĩa bị bỏ qua, option SELECT thiếu label fallback về code
option. Không có thuộc tính resolve được thì rỗng; không đổi `variant_attributes`.
Metadata được đọc chung cho cả batch (tối đa hai query), không truy vấn từng SKU.
Consumer chỉ dùng tên cho hiển thị; không suy diễn identity hoặc giá từ chuỗi này.

Caller gRPC gửi metadata `x-service-identity`; Product kiểm với `product.internal.grpc-clients` (mặc định
`cart-service`, `checkout-service`): thiếu là `UNAUTHENTICATED`, ngoài danh sách là `PERMISSION_DENIED`
([contracts ADR-004](../../contracts/decisions/ADR-004-service-identity-allowlist.md),
[ADR-022](decisions/ADR-022-grpc-caller-allowlist-and-reflection-off.md)). Warm-up loopback gửi identity của
caller đầu tiên trong danh sách.

`GetBatchSkus` nhận tối đa 100 `sku_id` duy nhất; vượt giới hạn hoặc `sku_id` không phải UUID là
`INVALID_ARGUMENT`. SKU không tồn tại hoặc không catalog-active là kết quả nghiệp vụ trong response
(`not_found`/`unavailable`), không phải lỗi transport.

Mọi status lỗi Product tự trả mang `google.rpc.ErrorInfo` (`domain=ecommerce.product.v1`, `reason`, metadata
`retryable`), kèm `BadRequest` cho `INVALID_ARGUMENT` và `RetryInfo` cho `UNAVAILABLE`
([contracts ADR-006](../../contracts/decisions/ADR-006-grpc-error-details.md), [ADR-023](decisions/ADR-023-grpc-error-details.md)).
Sáu `reason` (`SERVICE_IDENTITY_MISSING`, `SERVICE_IDENTITY_NOT_ALLOWED`, `SKU_ID_INVALID`, `BATCH_TOO_LARGE`,
`CATALOG_STORE_UNAVAILABLE`, `INTERNAL_ERROR`) nằm ở
[`errors.yaml`](../../contracts/grpc/ecommerce/product/v1/errors.yaml); `ProductGrpcErrorCatalogTest` fail khi enum lệch file.
Lỗi database là `UNAVAILABLE` `CATALOG_STORE_UNAVAILABLE`, không còn `UNKNOWN`.

Proto nguồn chuẩn tại
[`contracts/grpc/ecommerce/product/v1/product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto)
đã diễn đạt `price` (selling price), `base_price`, `catalog_revision`, `category_ids`
và catalog saleability; field tồn kho cũ vẫn được reserve vĩnh viễn. `product-service/proto` giữ
một bản copy cho Docker build, được `ProtoCopyDriftTest` ép giống hệt từng byte; sửa proto ở
`contracts/` rồi chép lại.

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
| `t.category.events` / `category-upserted`, `category-deleted` | Search | Category snapshot/tombstone; `sequence = category_revision`; snapshot có path, `is_leaf` và `facet_definitions` dẫn xuất. |
| `t.catalog.control.events` / `catalog-reindex-requested` | Search | Rename, đổi slug hoặc đổi parent Category yêu cầu Search coalesce rồi full reindex; Kafka key là `root_category_id`. |

Category event dùng Kafka key `category_id`; Category revision tăng với mutation category,
mapping category–attribute, hoặc đổi tên một attribute đang được category map. Attribute/option
không có event độc lập ở v1.

Mọi category snapshot và item của category export mang `facet_definitions`: danh sách
`{code, label, type}` của các attribute được map **trực tiếp** vào category, sắp theo `code`,
rỗng khi chưa có mapping ([ADR-019](./decisions/ADR-019-category-facet-definitions.md)).
`label` là `attributes.name`, `type` là `attributes.value_type`; descriptor không mang option,
`is_required` hay `is_variant`. Event và export cho ra cùng giá trị nên full reindex không lệch
luồng event. Đổi `code`/`value_type` của attribute đã map bị chặn bằng `ATTRIBUTE_IN_USE`, nên
chỉ đổi `name` làm snapshot lỗi thời: Product tăng `category_revision` và phát lại snapshot
`CATEGORY_ATTRIBUTE_CHANGED` cho mọi category map attribute đó. Tạo/sửa/xóa option không làm
đổi descriptor nên không phát event. Search dùng `label` làm nhãn nhóm facet
([Search interfaces](../search/interfaces.md)); nhãn từng option vẫn dẫn xuất từ token cho tới khi một
ADR khác đưa option vào descriptor.

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

## Search product-card consumer (2026-09-30)

[Search ADR-020](../search/decisions/ADR-020-product-cards-with-cheapest-eligible-sku.md) gom SKU thành một card/Product và chọn SKU giá thấp nhất thỏa bộ lọc. Product vẫn công bố snapshot từng SKU với giá/thuộc tính/thumbnail và revision; không thêm best-price field, không đổi event/export/API. Search sở hữu query-dependent representative; Product không chọn SKU thay cho Search.
