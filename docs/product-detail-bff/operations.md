---
doc_id: product-detail-bff-operations
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Operations

Theo dõi latency public PDP, lỗi Product buyer-read, Kafka consumer lag, tuổi snapshot
Inventory, số SKU `UNKNOWN`, số event bị revision guard chặn và kết quả bootstrap.
Không dùng `sku_id`, `product_id` hoặc slug làm metric label.

Alert khi projection lag/freshness vượt budget, bootstrap thất bại, hoặc `UNKNOWN` tăng
bất thường. Runbook rebuild: dừng public trạng thái `IN_STOCK` nếu cần, export Inventory,
import, replay offsets, đối soát sample rồi mở lại trạng thái healthy.

Nếu Product không sẵn sàng, PDP trả `503`. Nếu chỉ availability hỏng, catalog vẫn có
thể phục vụ với `UNKNOWN`; policy CTA được Storefront xác định nhưng không được khẳng định
hàng còn bán.
