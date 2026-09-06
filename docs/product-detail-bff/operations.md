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

Theo dõi riêng Review internal-call latency/error/timeout, connection-pool saturation và
tỷ lệ response có `review_section.status = UNAVAILABLE`. Không gộp lỗi Review vào metric
Product dependency vì hai dependency có severity và degraded behavior khác nhau.

Alert khi projection lag/freshness vượt budget, bootstrap thất bại, hoặc `UNKNOWN` tăng
bất thường. Runbook rebuild: dừng public trạng thái `IN_STOCK` nếu cần, export Inventory,
import, replay offsets, đối soát sample rồi mở lại trạng thái healthy.

Nếu Product không sẵn sàng, PDP trả `503`. Nếu chỉ availability hỏng, catalog vẫn có
thể phục vụ với `UNKNOWN`; policy CTA được Storefront xác định nhưng không được khẳng định
hàng còn bán.

Nếu Review không sẵn sàng, PDP vẫn trả catalog/availability và review section
`UNAVAILABLE`. Alert khi degraded rate vượt budget; không circuit-break bằng cách trả
aggregate 0 vì điều đó biến lỗi hạ tầng thành dữ liệu nghiệp vụ sai.
