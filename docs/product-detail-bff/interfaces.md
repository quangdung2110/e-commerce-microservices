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

`404` khi Product không tồn tại hoặc không catalog-active. Product không sẵn sàng là
`503`; availability query unavailable không biến request thành `503`, mà trả
`UNKNOWN` cho SKU bị ảnh hưởng. Review timeout/lỗi cũng không biến request thành `503`:
BFF trả `review_section.status = UNAVAILABLE` và không trả aggregate giả bằng 0. Review
trả thành công nhưng chưa có dữ liệu là `AVAILABLE` với aggregate 0 và preview rỗng.

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
BFF truyền correlation ID và tối đa 500 SKU unique trong một batch. BFF chỉ gọi sau khi
Product trả catalog-active SKU; response Inventory không được dùng để tạo hoặc lộ
catalog.

Không có BFF → Search interaction, Kafka consumer, Inventory export/bootstrap hoặc
database access. Inventory call phải có timeout/bulkhead/circuit breaker riêng. Timeout,
`503`, item thiếu hoặc item `UNKNOWN` chỉ degrade SKU tương ứng thành `UNKNOWN`.
