---
doc_id: product-detail-bff-interfaces
service: product-detail-bff
scope: target
status: proposed
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

Response chứa Product name/description/media/category, SKU variant/price/status,
`availability_status` (`IN_STOCK`, `OUT_OF_STOCK`, `UNKNOWN`) và Review enrichment:
`review_section.status`, aggregate cùng preview hữu hạn khi status là `AVAILABLE`.
Response không hứa quotation, reserve hoặc số lượng còn bán chính xác ở checkout.

`404` khi Product không tồn tại, không catalog-active hoặc slug không khớp pattern của
contract (khi đó BFF không gọi Product). Product không sẵn sàng là `503`;
availability query unavailable không biến request thành `503`, mà trả
`UNKNOWN` cho SKU bị ảnh hưởng. Review timeout/lỗi cũng không biến request thành `503`:
BFF trả `review_section.status = UNAVAILABLE` và không trả aggregate giả bằng 0. Review
trả thành công nhưng chưa có dữ liệu là `AVAILABLE` với aggregate 0 và preview rỗng.

`404` và `503` dùng `application/problem+json` theo
[HTTP convention](../../contracts/conventions/http.md#error): `404` có
`code = PRODUCT_DETAIL_NOT_FOUND`, `retryable = false`; `503` có
`code = PRODUCT_DETAIL_UNAVAILABLE`, `retryable = true` và `trace_id`. `503` kèm
`Retry-After` khi circuit breaker của Product đang mở (thời gian mở còn lại, làm tròn lên
giây). `200` có ít nhất một SKU `UNKNOWN` hoặc `review_section.status = UNAVAILABLE`, và
mọi `503`, mang `Cache-Control: no-store`; `200` đầy đủ theo cache policy của storefront,
mặc định `no-store` ([data.md](./data.md#cache-boundary)).

Preview chỉ phục vụ initial render. Cursor pagination đầy đủ, tạo/sửa review, upload
media, report và admin moderation dùng API Review trực tiếp qua Gateway.

## Internal dependencies

| Interaction | Provider | Contract | Semantics |
|---|---|---|---|
| Buyer catalog read | Product | `GET /internal/v1/product-details/{slug}` | mTLS; không có stock field |
| Batch availability read | Inventory | `POST /internal/inventory/v1/sku-availabilities:batch-get` | mTLS, Product Detail BFF only; one item mỗi SKU, advisory state only |
| PDP Review read | Review | `GET /internal/v1/reviews/products/{product_id}/pdp-summary` | Service identity; aggregate + preview `APPROVED`; optional enrichment |

Canonical Inventory contract nằm tại
[`contracts/http/inventory/read/v1/openapi.yaml`](../../contracts/http/inventory/read/v1/openapi.yaml).
BFF gửi tối đa 500 SKU unique trong một batch. BFF chỉ gọi sau khi
Product trả catalog-active SKU; response Inventory không được dùng để tạo hoặc lộ
catalog.

Mọi internal call mang `X-Service-Identity: product-detail-bff`, `traceparent`/`tracestate`
và `X-Request-Id` theo [HTTP convention](../../contracts/conventions/http.md#header).
Inventory contract yêu cầu thêm `X-Correlation-ID`; BFF đặt cùng giá trị với
`X-Request-Id`, hoặc trace ID của `traceparent` khi request không có `X-Request-Id`.

Không có BFF → Search interaction, Kafka consumer, Inventory export/bootstrap hoặc
database access. Mỗi dependency có timeout/bulkhead/circuit breaker riêng và không có
retry ở ứng dụng ([operations.md](./operations.md#resilience-và-phân-chia-với-meshgateway)).
Item thiếu hoặc item `UNKNOWN` chỉ degrade SKU tương ứng thành `UNKNOWN`; call Inventory
không cho kết quả hợp lệ (timeout, `5xx`, `4xx`, sai contract, circuit open, bulkhead đầy,
hết budget) degrade mọi SKU thành `UNKNOWN`.
