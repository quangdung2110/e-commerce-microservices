---
doc_id: product-detail-bff-data
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Data Design

## Ownership

Product Detail BFF không sở hữu persistent database, stock projection, Kafka offset,
bootstrap cursor hoặc Review store. `SkuAvailabilityState` và mọi cache/read replica
cho availability thuộc Inventory; catalog thuộc Product; review/rating thuộc Review.

BFF chỉ giữ dữ liệu trong phạm vi request để compose response. Không được thêm foreign
key, join database hoặc access credential tới Product, Inventory hay Review database.

## Cache boundary

Cache không phải requirement của BFF. Nếu bật CDN hoặc in-process cache để giảm traffic
PDP, entry phải là dữ liệu dẫn xuất, TTL-bounded và có thể bị xóa mà không cần recovery.
Cache không được là Redis/PostgreSQL projection bền vững của stock, không được replay
từ Kafka và không được dùng để quyết định reserve.

Cache key, TTL, cache-control và invalidation là storefront runtime policy; chúng phải
phù hợp freshness budget đã chốt. Khi cache miss hoặc cache bypass, BFF gọi Product,
Inventory và Review theo workflow chuẩn.

Response degrade không bao giờ được cache: `200` có ít nhất một SKU `UNKNOWN` hoặc
`review_section.status = UNAVAILABLE`, và mọi `503`, luôn mang `Cache-Control: no-store`
và không được ghi vào in-process cache. Nếu cache như vậy, CDN sẽ tiếp tục phục vụ trạng
thái degrade sau khi dependency đã phục hồi. Khi chưa có storefront cache policy, `200`
đầy đủ cũng mang `no-store`
([ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md)).

## Availability data received from Inventory

| Field | Ý nghĩa tại BFF |
|---|---|
| `sku_id` | Khóa join với SKU catalog-active do Product trả trong cùng request |
| `status` | `IN_STOCK`, `OUT_OF_STOCK` hoặc `UNKNOWN` do Inventory quyết định |
| `availability_revision` | Chỉ phục vụ trace/observability; BFF không persist hay guard revision |
| `evaluated_at` | Thời điểm Inventory xác nhận state; không phải thời điểm reserve hay quantity update |

Chỉ SKU Product trả về mới có thể xuất hiện trong public response. Một kết quả Inventory
cũ, thừa hoặc cho SKU không active bị bỏ qua; nó không thể làm Product lộ ra.

Không có bootstrap, import, export session, Kafka replay hoặc database rebuild trong
BFF. Recovery của availability read model là trách nhiệm nội bộ của Inventory.
