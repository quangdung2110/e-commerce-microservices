---
doc_id: product-detail-bff-operations
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Operations

## SLO

Giá trị khởi điểm theo [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md);
là release gate cần load test xác nhận trước khi public PDP.

| SLI | Mục tiêu | Đo |
|---|---:|---|
| PDP availability | 99.9 % / 30 ngày | Tỷ lệ response không phải `5xx`; `200` degrade và `404` tính là thành công |
| PDP latency tại BFF | p95 ≤ 300 ms, p99 ≤ 800 ms | Từ lúc BFF nhận request tới khi ghi xong response |
| Degrade do Inventory | ≤ 1 % request / 30 ngày | Request có mọi SKU `UNKNOWN` vì call Inventory không cho kết quả hợp lệ (lỗi, timeout, circuit open, bulkhead đầy, hết budget); không tính `UNKNOWN` do Inventory trả |
| Degrade do Review | ≤ 1 % request / 30 ngày | Request có `review_section.status = UNAVAILABLE` |

Latency kỳ vọng của provider để các timeout dưới đây có nghĩa:

| Dependency | Latency kỳ vọng | Nơi provider ghi nhận |
|---|---:|---|
| Product `GET /internal/v1/product-details/{slug}` | p99 ≤ 250 ms | [product/operations.md](../product/operations.md) |
| Inventory `POST /internal/inventory/v1/sku-availabilities:batch-get` (≤ 500 SKU) | p99 ≤ 200 ms | [inventory/operations.md](../inventory/operations.md) |
| Review `GET /internal/v1/reviews/products/{product_id}/pdp-summary` | p99 ≤ 200 ms | [review/operations.md](../review/operations.md) |

## Health và degradation

BFF chỉ ready khi có thể phục vụ request và Product buyer-read client được cấu hình.
Readiness không được phụ thuộc trực tiếp vào Inventory hoặc Review, vì hai dependency
đó có degradation behavior riêng. Circuit breaker mở cũng không làm BFF mất readiness.

| Dependency condition | Public behavior | Tính vào circuit breaker | Owner xử lý |
|---|---|---|---|
| Product timeout/lỗi kết nối/`5xx`/response sai contract | `503` PDP | Có | Product + Storefront |
| Product `400`/`401`/`403` | `503` PDP; alert cấu hình | Không | Storefront (+ Product nếu contract đổi) |
| Product circuit open | `503` PDP kèm `Retry-After`, không gọi Product | — | Product + Storefront |
| Product bulkhead đầy | `503` PDP | Không | Storefront |
| Product `404` hoặc slug sai pattern | `404` PDP | Không | — |
| Inventory timeout/lỗi kết nối/`5xx`/response sai contract | `200` PDP; mọi SKU `UNKNOWN` | Có | Inventory + Storefront |
| Inventory `400`/`401`/`403` | `200` PDP; mọi SKU `UNKNOWN`; alert cấu hình | Không | Storefront + Inventory |
| Inventory circuit open, bulkhead đầy hoặc hết budget sau pha Product | `200` PDP; mọi SKU `UNKNOWN`, không gọi Inventory | Không | Inventory + Storefront |
| Inventory thiếu item hoặc trả item `UNKNOWN` | `200` PDP; chính SKU đó là `UNKNOWN` | Không | Inventory |
| Review timeout/lỗi kết nối/`5xx`/response sai contract | `200` PDP; `review_section.status = UNAVAILABLE` | Có | Review + Storefront |
| Review `400`/`401`/`403` | `200` PDP; `UNAVAILABLE`; alert cấu hình | Không | Storefront + Review |
| Review circuit open, bulkhead đầy hoặc hết budget sau pha Product | `200` PDP; `UNAVAILABLE`, không gọi Review | Không | Review + Storefront |
| Client/gateway hủy request | BFF hủy mọi call đang chạy, không ghi response | Không | — |

Response sai contract gồm body không parse được hoặc vi phạm schema; với Inventory còn
gồm trùng `sku_id`, khi đó cả batch bị bỏ. Item Inventory cho SKU không được yêu cầu bị
bỏ qua và không tính là lỗi. Mọi `200` degrade và mọi `503` mang `Cache-Control: no-store`.

Không có Kafka consumer lag, stock projection age, export session hoặc projection
bootstrap runbook tại BFF.

## Resilience và phân chia với mesh/gateway

Quyết định đầy đủ nằm ở [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md).
Mesh và gateway xử lý sự cố hạ tầng; BFF quyết định budget, fallback và khi nào ngừng
gọi. Timeout ở BFF luôn ngắn hơn timeout mesh của cùng route.

| Dependency | Timeout BFF | Retry BFF | Timeout mesh | Retry mesh | Bulkhead khởi điểm |
|---|---:|---:|---:|---|---:|
| Product buyer-read | 400 ms | 0 | 3 giây | 2 lần, `connect-failure,refused-stream` | 100 |
| Inventory batch-read | `min(300 ms, deadline còn lại − 50 ms)` | 0 | 2 giây | 0 | 80 |
| Review PDP summary | `min(300 ms, deadline còn lại − 50 ms)` | 0 | 2 giây | 2 lần, `connect-failure,refused-stream` | 80 |

- **Deadline PDP** 1 000 ms tính từ lúc BFF nhận request, truyền qua request context.
  Product chạy trước; Inventory và Review chạy song song sau đó. Nếu deadline còn lại
  dưới 100 ms thì không gọi Inventory và Review. Gateway chờ response header của route
  PDP tối đa 3 giây và chỉ retry lỗi kết nối tới BFF
  ([gateway/runtime-contract.md §5](../gateway/runtime-contract.md#5-ma-trận-định-tuyến-bắt-buộc)).
- **Retry:** ứng dụng không retry dependency nào. Retry duy nhất là retry lỗi kết nối của
  mesh (Product, Review) theo
  [mesh/runtime-contract.md §7](../mesh/runtime-contract.md#7-traffic-policy-east-west).
  `Retry-After` trong `503` của Inventory không làm BFF chờ hay retry.
- **Circuit breaker** riêng cho từng dependency trong mỗi pod: cửa sổ 10 giây, tối thiểu
  20 call, mở khi tỷ lệ lỗi hoặc tỷ lệ call chậm ≥ 50 % (call chậm vượt 80 % timeout BFF:
  Product 320 ms, Inventory/Review 240 ms), mở 10 giây, half-open 5 call thử.
- **Bulkhead** là semaphore không hàng chờ, giới hạn theo
  `ceil(RPS mục tiêu mỗi pod × p99 kỳ vọng × 2)`; số trong bảng tính cho 200 RPS/pod.
  Connection pool của HTTP client không nhỏ hơn giới hạn bulkhead. `connectionPool`
  trong `DestinationRule` của mesh là giới hạn riêng, bảo vệ provider trước mọi caller.
- Batch size tối đa khớp contract Inventory là 500 SKU.

CDN/in-process cache là optional. Nếu bật, cache policy phải nêu TTL, invalidation,
cache key và behavior khi bypass; response degrade không bao giờ được cache
([data.md](./data.md#cache-boundary)). BFF không có persistent stock cache hoặc recovery
state. Không dùng SKU ID, product ID hoặc slug làm metric label.

## Observability và alerts

Theo dõi latency/error public PDP; theo từng dependency: latency, kết quả (`success`,
`timeout`, `connect_error`, `5xx`, `4xx`, `contract_violation`, `circuit_open`,
`bulkhead_rejected`, `budget_skipped`) và trạng thái circuit breaker; tỷ lệ SKU `UNKNOWN`
tách theo nguồn (Inventory trả, lỗi call, bỏ qua); tỷ lệ response
`review_section.status = UNAVAILABLE`. Tách lỗi Inventory và Review khỏi Product vì
severity/degraded behavior khác nhau. Mesh cung cấp golden metrics theo cạnh gọi; metric
trên là ngữ nghĩa mà chỉ BFF biết.

Alert khi PDP availability hoặc latency vượt SLO; circuit Product mở; circuit Inventory
hoặc Review mở liên tục quá 5 phút; tỷ lệ degrade vượt budget ở mục SLO; bất kỳ
`401`/`403` nào từ dependency (sai service identity hoặc `AuthorizationPolicy`); bất kỳ
`contract_violation` nào; `bulkhead_rejected` tăng đều (cần xem lại giới hạn hoặc số pod);
Inventory `UNKNOWN` do Inventory trả tăng bất thường. Inventory phải theo dõi freshness,
pending recompute và cache/read-model health của chính nó; BFF chỉ quan sát outcome của
query.

BFF truyền `traceparent`/`tracestate` và `X-Request-Id` tới cả ba dependency, và
`X-Correlation-ID` cùng giá trị tới Inventory ([interfaces.md](./interfaces.md#internal-dependencies)).

## Runbook

**Inventory incident:** xác nhận BFF đang map lỗi sang `UNKNOWN` và response mang
`no-store`, xem kết quả call và trạng thái circuit Inventory, kiểm tra latency/`5xx` của
Availability Read API theo `X-Correlation-ID`, rồi chuyển cho Inventory on-call sửa read
model/cache. Không khôi phục bằng cách replay topic hoặc sửa database BFF vì BFF không có
projection đó. Không tắt circuit breaker để "ép" gọi Inventory.

**Review incident:** tương tự, với `UNAVAILABLE` thay cho `UNKNOWN`; chuyển Review on-call.

**Product incident:** PDP trả `503`. Kiểm tra circuit Product và kết quả call; `401`/`403`
là lỗi cấu hình identity/mesh (Storefront + platform), `5xx`/timeout chuyển Product
on-call.

**`bulkhead_rejected` hoặc latency tăng mà dependency khỏe:** BFF thiếu capacity; scale
BFF hoặc hiệu chỉnh giới hạn bulkhead theo công thức trên, không tăng timeout.
