---
doc_id: product-detail-bff-development
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Development Guide

Implementation cần Product internal client, Inventory Availability Read client và
Review internal client. Không cần PostgreSQL, Kafka, Elasticsearch hoặc access tới
database của Product/Inventory/Review.

Test bắt buộc: Product nhiều SKU với trạng thái Inventory mixed; batch tối đa 500 SKU;
Inventory thiếu một item; Inventory trả `UNKNOWN`; Inventory timeout/5xx/circuit open;
Product `HIDDEN`; Product timeout; slug sai pattern trả `404` mà không gọi Product;
Order Reserve thất bại sau khi PDP đã hiện `IN_STOCK`; Review có aggregate 0; Review có
preview; Review timeout/5xx; và response Inventory/Review/Product sai contract (gồm
Inventory trùng `sku_id`).

Test resilience theo [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md):
Product chậm làm deadline còn dưới 100 ms thì không gọi Inventory/Review; timeout BFF
kích hoạt trước timeout mesh; không có retry ở ứng dụng cho cả ba dependency; circuit
breaker mở, half-open rồi đóng cho từng dependency, và circuit Review mở không ảnh hưởng
Product/Inventory; Product circuit open trả `503` kèm `Retry-After`; bulkhead từ chối
theo đúng mapping và không tính vào circuit breaker; `400`/`401`/`403` từ từng dependency
được map đúng, phát alert và không tính vào circuit breaker; client hủy request thì mọi
call con bị hủy; mọi response degrade và `503` mang `Cache-Control: no-store`.

Definition of Done: public OpenAPI và cả ba internal dependency contract có contract
test; BFF gửi đúng một Inventory batch call sau khi Product trả SKU; mTLS/service
identity, `traceparent`, `X-Request-Id` (và `X-Correlation-ID` cho Inventory) được
truyền; Inventory failure map đúng `UNKNOWN`; Review failure map đúng `UNAVAILABLE`;
`404`/`503` là `application/problem+json`; observability/circuit breaker/resilience test
pass; load test xác nhận SLO và latency kỳ vọng của provider ở
[operations.md](./operations.md#slo); và không có database migration, Kafka consumer,
export/bootstrap client, Review table/consumer/command handler hoặc Search dependency
trong BFF.
