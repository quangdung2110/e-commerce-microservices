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
Product `HIDDEN`; Product timeout; Order Reserve thất bại sau khi PDP đã hiện
`IN_STOCK`; Review có aggregate 0; Review có preview; Review timeout/5xx; và response
Inventory/Review sai contract.

Definition of Done: public OpenAPI và cả ba internal dependency contract có contract
test; BFF gửi đúng một Inventory batch call sau khi Product trả SKU; mTLS/service
identity và correlation ID được truyền; Inventory failure map đúng `UNKNOWN`; Review
failure map đúng `UNAVAILABLE`; observability/circuit breaker test pass; và không có
database migration, Kafka consumer, export/bootstrap client, Review table/consumer/
command handler hoặc Search dependency trong BFF.
