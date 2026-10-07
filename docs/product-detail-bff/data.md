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
phù hợp freshness budget đã chốt. `catalog_revision` không được dùng một mình làm cache
key hay điều kiện invalidation: category và nhãn thuộc tính đổi mà không tăng revision
này. Khi cache miss hoặc cache bypass, BFF gọi Product, Inventory và Review theo workflow
chuẩn.

Response degrade không bao giờ được cache: `200` có ít nhất một SKU `UNKNOWN` hoặc
`review_section.status = UNAVAILABLE`, và mọi `503`, luôn mang `Cache-Control: no-store`
và không được ghi vào in-process cache. Nếu cache như vậy, CDN sẽ tiếp tục phục vụ trạng
thái degrade sau khi dependency đã phục hồi. Khi chưa có storefront cache policy, `200`
đầy đủ cũng mang `no-store`
([ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md)).

`404` cũng luôn mang `no-store`: theo RFC 9111, CDN được phép cache heuristic một `404`
không có header, khi đó Product vừa được activate vẫn hiện "không tìm thấy"
([ADR-007](./decisions/ADR-007-hot-path-clarifications.md)).

## Catalog data received from Product

Buyer-read trả `BuyerProductDetail`
([Product ADR-018](../product/decisions/ADR-018-resolved-buyer-product-detail-projection.md)).
BFF map gần như 1:1 sang public response, chỉ khác ở các điểm dưới đây.

| Field Product | Tại BFF |
|---|---|
| `category` (kèm `path`), `attributes[]`, `variant_axes[]` | Chép nguyên, giữ thứ tự Product trả |
| `media[].storage_key` | Đổi thành `media[].url` = CDN base URL + storage key percent-encode từng segment; storage key không ra public |
| `skus[].thumbnail_storage_key` | Đổi thành `skus[].thumbnail_url`; `null` thành URL placeholder cấu hình |
| `catalog_revision` | Chép nguyên; chỉ là revision Product aggregate, không đại diện category/nhãn |
| `updated_at` | Không đưa ra public response |

Media tối đa 20 cấp Product và 10 mỗi SKU do Product cắt; BFF không cắt thêm. Ngoài schema,
BFF coi các vi phạm tham chiếu sau là response sai contract: `media[].sku_id` không có
trong `skus[]`, giá trị `variant_attributes` không có option trong `variant_axes`, trùng
`sku_id` hoặc `media_id` ([ADR-005](./decisions/ADR-005-pdp-catalog-scope-and-media-url.md)).

## Availability data received from Inventory

| Field | Ý nghĩa tại BFF |
|---|---|
| `sku_id` | Khóa join với SKU catalog-active do Product trả trong cùng request |
| `status` | `IN_STOCK`, `OUT_OF_STOCK` hoặc `UNKNOWN` do Inventory quyết định |
| `availability_revision` | Chỉ phục vụ trace/observability; BFF không persist hay guard revision |
| `evaluated_at` | Thời điểm Inventory xác nhận state; không phải thời điểm reserve hay quantity update |

Chỉ SKU Product trả về mới có thể xuất hiện trong public response. Một kết quả Inventory
thừa hoặc cho SKU không được yêu cầu bị bỏ qua; nó không thể làm Product lộ ra. Item thừa
và item thiếu đều vi phạm cam kết một item mỗi SKU của Inventory nên được ghi
`contract_violation` mức item
([ADR-007](./decisions/ADR-007-hot-path-clarifications.md)).

Không có bootstrap, import, export session, Kafka replay hoặc database rebuild trong
BFF. Recovery của availability read model là trách nhiệm nội bộ của Inventory.
