---
doc_id: product-detail-bff-operations
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Operations

## Health và degradation

BFF chỉ ready khi có thể phục vụ request và Product buyer-read client được cấu hình.
Readiness không được phụ thuộc trực tiếp vào Inventory hoặc Review, vì hai dependency
đó có degradation behavior riêng.

| Dependency condition | Public behavior | Owner xử lý |
|---|---|---|
| Product buyer-read timeout/5xx | `503` PDP | Product + Storefront |
| Inventory batch-read timeout/5xx/circuit open | `200` PDP; SKU bị ảnh hưởng là `UNKNOWN` | Inventory + Storefront |
| Inventory trả item `UNKNOWN` | `200` PDP; chính SKU đó là `UNKNOWN` | Inventory |
| Review timeout/5xx/circuit open | `200` PDP; `review_section.status = UNAVAILABLE` | Review + Storefront |

Không có Kafka consumer lag, stock projection age, export session hoặc projection
bootstrap runbook tại BFF.

## Configuration và resilience

BFF cấu hình riêng timeout, connection pool, bulkhead và circuit breaker cho Product,
Inventory và Review. Tất cả deadline con phải nằm trong request budget của PDP; không
retry nhiều lần ở buyer hot path vì retry có thể khuếch đại outage. Batch size tối đa
phải khớp contract Inventory là 500 SKU.

CDN/in-process cache là optional. Nếu bật, cache policy phải nêu TTL, invalidation,
cache key và behavior khi bypass; BFF không có persistent stock cache hoặc recovery
state. Không dùng SKU ID, product ID hoặc slug làm metric label.

## Observability và alerts

Theo dõi latency/error public PDP, lỗi Product buyer-read, latency/error/timeout của
Inventory batch read, tỷ lệ SKU `UNKNOWN`, circuit breaker state và tỷ lệ response
`review_section.status = UNAVAILABLE`. Tách lỗi Inventory và Review khỏi Product vì
severity/degraded behavior khác nhau.

Alert khi Product error vượt SLO, Inventory `UNKNOWN` rate tăng bất thường, Inventory
batch-read timeout/error vượt budget hoặc Review degradation vượt budget. Inventory
phải theo dõi freshness, pending recompute và cache/read-model health của chính nó;
BFF chỉ quan sát outcome của query.

Runbook Inventory incident: xác nhận BFF đang map lỗi sang `UNKNOWN`, kiểm tra latency/
5xx của Availability Read API và correlation ID, rồi chuyển cho Inventory on-call sửa
read model/cache. Không khôi phục bằng cách replay topic hoặc sửa database BFF vì BFF
không có projection đó.
