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

Canonical contract sẽ nằm tại `contracts/http/product-detail-bff/v1/openapi.yaml`.

| Endpoint | Caller | Hành vi |
|---|---|---|
| `GET /api/v1/storefront/products/{slug}` | Buyer qua Gateway | Trả Product detail catalog và availability cho từng SKU |

Response chứa Product name/description/media/category, SKU variant/price/status và
`availability_status` (`IN_STOCK`, `OUT_OF_STOCK`, `UNKNOWN`). Response không hứa
quotation, reserve hoặc số lượng còn bán chính xác ở thời điểm checkout.

`404` khi Product không tồn tại hoặc không catalog-active. Product không sẵn sàng là
`503`; availability projection stale không biến request thành `503`, mà trả `UNKNOWN`.

## Internal dependencies

| Interaction | Provider | Contract | Semantics |
|---|---|---|---|
| Buyer catalog read | Product | `GET /internal/v1/product-details/{slug}` | mTLS; không có stock field |
| Stock snapshot | Inventory | `t.inventory.stock.events.v1` | Consume full snapshot, guard `stock_revision` |
| Bootstrap export | Inventory | `GET /internal/v1/inventory/stock-snapshots` | Chỉ startup/recovery, không gọi per request |

Không có BFF → Search interaction. Không có BFF → Inventory synchronous availability
query trong decision hiện tại.
