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
`503`; availability projection stale không biến request thành `503`, mà trả `UNKNOWN`.
Review timeout/lỗi cũng không biến request thành `503`: BFF trả
`review_section.status = UNAVAILABLE` và không trả aggregate giả bằng 0. Review trả
thành công nhưng chưa có dữ liệu là `AVAILABLE` với aggregate 0 và preview rỗng.

Preview chỉ phục vụ initial render. Cursor pagination đầy đủ, tạo/sửa review, upload
media, report và admin moderation dùng API Review trực tiếp qua Gateway.

## Internal dependencies

| Interaction | Provider | Contract | Semantics |
|---|---|---|---|
| Buyer catalog read | Product | `GET /internal/v1/product-details/{slug}` | mTLS; không có stock field |
| Stock snapshot | Inventory | `t.inventory.stock.events.v1` | Consume full snapshot, guard `stock_revision` |
| Bootstrap export | Inventory | `GET /internal/v1/inventory/stock-snapshots` | Chỉ startup/recovery, không gọi per request |
| PDP Review read | Review | `GET /internal/v1/reviews/products/{product_id}/pdp-summary` | Service identity; aggregate + preview `APPROVED`; optional enrichment |

Không có BFF → Search interaction. Không có BFF → Inventory synchronous availability
query trong decision hiện tại.

BFF không consume `t.review.events`, không gọi Search để lấy rating và không truy cập
database Review. Review internal call phải có timeout/bulkhead riêng để lỗi enrichment
không làm cạn request pool của PDP.
