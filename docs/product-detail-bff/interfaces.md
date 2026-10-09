---
doc_id: product-detail-bff-interfaces
service: product-detail-bff
scope: current
status: implemented
owners:
  - Storefront team
---

# Product Detail BFF — Interfaces

## Public API

Canonical contract nằm tại
[`contracts/http/product-detail-bff/v1/openapi.yaml`](../../contracts/http/product-detail-bff/v1/openapi.yaml).

| Endpoint | Caller | Hành vi |
|---|---|---|
| `GET /api/v1/storefront/products/{slug}` | Buyer qua Gateway | Trả catalog, availability theo SKU và Review summary/preview |

Response chứa:

| Phần | Nội dung | Nguồn |
|---|---|---|
| Product | `product_id`, `name`, `slug`, `description`, `catalog_revision` | Product |
| `category` | Category của Product kèm `path` breadcrumb từ gốc tới lá | Product |
| `attributes[]` | Bảng thông số: `code`, `name`, `value_type`, `value`, `value_label` (chỉ `SELECT`) | Product |
| `variant_axes[]` | Bộ chọn biến thể: trục cùng `options[]` (`value`, `label`) đang được SKU dùng | Product |
| `media[]` | Gallery phẳng: `media_id`, `sku_id` (null là cấp Product), `media_type`, `url`, `alt_text`, `sort_order`; tối đa 20 cấp Product và 10 mỗi SKU | Product; BFF dựng `url` |
| `skus[]` | `sku_id`, `sku_code`, `variant_attributes` (code), `thumbnail_url`, `base_price_vnd`, `selling_price_vnd`, `availability_status` (`IN_STOCK`, `OUT_OF_STOCK`, `UNKNOWN`), `availability_observed_at` | Product; Inventory cho availability; BFF dựng `thumbnail_url` |
| `review_section` | `status`, aggregate cùng preview hữu hạn khi status là `AVAILABLE` | Review |

Thứ tự thuộc tính, trục và option là thứ tự Product trả (theo tên, collation tiếng Việt,
tie-break `code`; option `SELECT` theo `sort_order`). `url` và `thumbnail_url` là URL CDN
công khai; SKU không ảnh nhận URL placeholder; public response không chứa storage key
([ADR-005](./decisions/ADR-005-pdp-catalog-scope-and-media-url.md)). Response không hứa
quotation, reserve hoặc số lượng còn bán chính xác ở checkout.

`404` khi Product không tồn tại, không catalog-active hoặc slug không khớp pattern của
contract (khi đó BFF không gọi Product; pattern và độ dài tối đa 180 trùng với
PRODUCT-BR-013 của Product). Gateway chuyển cả prefix `/api/v1/storefront/products/` tới BFF,
nên đường dẫn không phải một slug (rỗng, có `/` cuối, nhiều segment) cũng là slug sai pattern
và nhận cùng `404` problem `no-store`, không phải lỗi mặc định của framework. Product không
sẵn sàng, hoặc deadline còn dưới 100 ms trước khi gọi Product, là `503`;
availability query unavailable không biến request thành `503`, mà trả
`UNKNOWN` cho SKU bị ảnh hưởng. Review timeout/lỗi cũng không biến request thành `503`:
BFF trả `review_section.status = UNAVAILABLE` và không trả aggregate giả bằng 0. Review
trả thành công nhưng chưa có dữ liệu là `AVAILABLE` với aggregate 0 và preview rỗng.

`404` và `503` dùng `application/problem+json` theo
[HTTP convention](../../contracts/conventions/http.md#error): `404` có
`code = PRODUCT_DETAIL_NOT_FOUND`, `retryable = false`; `503` có
`code = PRODUCT_DETAIL_UNAVAILABLE`, `retryable = true` và `trace_id`. `503` kèm
`Retry-After` khi circuit breaker của Product đang mở (thời gian mở còn lại, làm tròn lên
giây). `200` có ít nhất một SKU `UNKNOWN` hoặc `review_section.status = UNAVAILABLE`, mọi
`404` và mọi `503` mang `Cache-Control: no-store`; `200` đầy đủ theo cache policy của
storefront, mặc định `no-store` ([data.md](./data.md#cache-boundary)).

Preview chỉ phục vụ initial render. Cursor pagination đầy đủ, tạo/sửa review, upload
media, report và admin moderation dùng API Review trực tiếp qua Gateway.

## Internal dependencies

| Interaction | Provider | Contract | Semantics |
|---|---|---|---|
| Buyer catalog read | Product | `GET /internal/v1/product-details/{slug}` | mTLS; `BuyerProductDetail` đã resolve category path, thuộc tính, trục biến thể, media 20/10; không có stock field, không ETag |
| Batch availability read | Inventory | `POST /internal/inventory/v1/sku-availabilities:batch-get` | mTLS, Product Detail BFF only; one item mỗi SKU, advisory state only |
| PDP Review read | Review | `GET /internal/v1/reviews/products/{product_id}/pdp-summary?preview_limit=3` | mTLS + `Authorization: Bearer`; aggregate + preview `APPROVED`; optional enrichment |

Canonical Inventory contract nằm tại
[`contracts/http/inventory/read/v1/openapi.yaml`](../../contracts/http/inventory/read/v1/openapi.yaml).
BFF gửi tối đa 500 SKU unique trong một batch. BFF chỉ gọi sau khi
Product trả catalog-active SKU; response Inventory không được dùng để tạo hoặc lộ
catalog.

### Header gửi cho dependency

| Header | Product | Inventory | Review |
|---|---|---|---|
| `X-Service-Identity: product-detail-bff` | Có (Product trả `401` nếu thiếu) | Có | Có |
| `Authorization: Bearer <PDP_REVIEW_SERVICE_TOKEN>` | — | — | Có; Review trả `401` nếu thiếu, `403` nếu token không thuộc `REVIEW_INTERNAL_PDP_TOKENS` |
| `traceparent`/`tracestate` | Có | Có | Có |
| `X-Request-Id` (request ID đã chuẩn hóa) | Có | — | Có |
| `X-Correlation-ID` (cùng giá trị request ID) | — | Có (bắt buộc) | — |

**Request ID đã chuẩn hóa.** BFF chỉ dùng `X-Request-Id` từ gateway/client khi giá trị
khớp `^[A-Za-z0-9._:-]{8,128}$`; nếu thiếu hoặc không khớp, BFF dùng trace ID (32 ký tự
hex) của span hiện tại. Giá trị client gửi sai không làm request lỗi, chỉ bị thay thế. Nhờ
vậy client không thể khiến Inventory hay Review trả `400` (hai contract này yêu cầu độ dài
8–128), tức không thể cố ý làm PDP degrade hay bắn alert cấu hình
([ADR-007](./decisions/ADR-007-hot-path-clarifications.md)).

**Review token.** Mesh `AuthorizationPolicy` theo principal là lớp kiểm soát chính; Review
còn giữ static bearer allowlist làm defense-in-depth, nên BFF phải gửi token lấy từ secret
manager qua `PDP_REVIEW_SERVICE_TOKEN`. Xoay vòng: thêm token mới vào
`REVIEW_INTERNAL_PDP_TOKENS`, roll BFF sang token mới, rồi gỡ token cũ khỏi Review.
`preview_limit` lấy từ `PDP_REVIEW_PREVIEW_LIMIT` (mặc định 3, tối đa 5 theo public
contract).

Không có BFF → Search interaction, Kafka consumer, Inventory export/bootstrap hoặc
database access. Mỗi dependency có timeout/bulkhead/circuit breaker riêng và không có
retry ở ứng dụng ([operations.md](./operations.md#resilience-và-phân-chia-với-meshgateway)).
Item thiếu hoặc item `UNKNOWN` chỉ degrade SKU tương ứng thành `UNKNOWN`; call Inventory
không cho kết quả hợp lệ (timeout, `5xx`, `4xx`, sai contract, circuit open, bulkhead đầy,
hết budget) degrade mọi SKU thành `UNKNOWN`. Item thiếu hoặc item cho SKU không được yêu
cầu vi phạm cam kết "exactly one item per SKU" của Inventory: BFF không bỏ cả batch nhưng
ghi vi phạm mức item (counter và dòng log kèm request ID) để lỗi này không bị che
([operations.md](./operations.md#health-và-degradation)). Khi Inventory hoặc Review bị tắt
bằng cờ cấu hình, BFF không gọi dependency đó (outcome `disabled`, xem
[operations.md](./operations.md#cấu-hình)).
