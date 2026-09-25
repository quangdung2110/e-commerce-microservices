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

## Stack và cấu trúc

Theo [ADR-006](./decisions/ADR-006-java-spring-webflux-runtime.md): Java 21, Spring Boot
3.3.x WebFlux trên Reactor Netty, `WebClient` riêng cho mỗi dependency, Resilience4j
(`CircuitBreaker`, `Bulkhead` semaphore; không `Retry`), Micrometer + Prometheus,
Micrometer Tracing bridge OpenTelemetry. Module `java/services/product-detail-bff`, package
gốc `com.ecommerce.pdp`. Pipeline request không được gọi blocking I/O; BlockHound chạy
trong test.

Response dependency đọc theo tolerant reader: bỏ qua field không biết; thiếu field bắt
buộc, sai kiểu/enum/giới hạn hoặc vi phạm ràng buộc tham chiếu mới là sai contract.

## Chạy local

Máy dev không cần JDK: build bằng container `maven:3.9-eclipse-temurin-21` như các service
Java khác. `docker-compose.yml` có service `product-detail-bff` (host port `8093`) và route
qua gateway tại `http://localhost:8088/api/v1/storefront/products/{slug}`:

| Thành phần local | Giá trị |
|---|---|
| Inventory | `PDP_INVENTORY_ENABLED=false`: chưa có Inventory service, mọi SKU `UNKNOWN` với outcome `disabled` |
| Review | `PDP_REVIEW_SERVICE_TOKEN=dev-product-detail-bff-token`, khớp `REVIEW_INTERNAL_PDP_TOKENS` của review-service |
| Media ("CDN" local) | Bucket MinIO `product-media` (anonymous read) phát qua gateway tại `http://localhost:8088/media/<storage_key>`; `PDP_MEDIA_REQUIRE_HTTPS=false` |
| Placeholder | `http://localhost:8088/media/placeholder/product.svg`, file nằm ở `infra/minio/product-media/` và được `minio-init` upload |

Search dùng đúng hai giá trị media này (`CDN_BASE_URL`, `THUMBNAIL_PLACEHOLDER_URL`) và dựng
URL cùng cách, nên thẻ sản phẩm ở trang danh sách và PDP trỏ cùng một file.

Product không có API upload: admin chỉ ghi `storage_key`. Muốn thấy ảnh thật ở local, đặt
file vào bucket đúng key đó, ví dụ với key `products/watch/hero.jpg`:

```bash
docker run --rm --network ecommerce-network -v "$PWD:/work" --entrypoint /bin/sh minio/mc:RELEASE.2024-01-18T07-03-39Z -c "mc alias set local http://minio:9000 minioadmin minioadmin && mc cp /work/hero.jpg local/product-media/products/watch/hero.jpg"
```

Cấu hình đầy đủ ở [operations.md](./operations.md#cấu-hình).

Integration test không phụ thuộc service thật: WireMock giả lập Product, Inventory và
Review theo đúng contract, kể cả khi test bật Inventory.

## Test bắt buộc

Product nhiều SKU với trạng thái Inventory mixed; batch tối đa 500 SKU;
Inventory thiếu một item và trả item cho SKU không yêu cầu (SKU khác giữ kết quả, metric
`contract_violation` mức item tăng); Inventory trả `UNKNOWN`; Inventory timeout/5xx/circuit
open; Product trả `404` (gồm Product `HIDDEN`) thì `404` kèm `no-store`; Product timeout;
slug sai pattern hoặc dài hơn 180 trả `404` `no-store` mà không gọi Product;
Order Reserve thất bại sau khi PDP đã hiện `IN_STOCK`; Review có aggregate 0; Review có
preview; Review timeout/5xx; và response Inventory/Review/Product sai contract (gồm
Inventory trùng `sku_id`); field thừa trong response dependency không làm lỗi.

Test header và cấu hình theo [ADR-007](./decisions/ADR-007-hot-path-clarifications.md):
`X-Request-Id` hợp lệ được chuyển tiếp; thiếu, ngắn hơn 8, dài hơn 128 hoặc có ký tự lạ thì
được thay bằng trace ID và không bao giờ tới Inventory/Review; Review nhận
`Authorization: Bearer` đúng token và `preview_limit` theo cấu hình; thiếu
`PDP_REVIEW_SERVICE_TOKEN` khi Review bật thì không ready; cờ `PDP_INVENTORY_ENABLED=false`
/ `PDP_REVIEW_ENABLED=false` không phát request nào tới dependency, outcome `disabled`,
không tính SLO degrade.

Test catalog theo [ADR-005](./decisions/ADR-005-pdp-catalog-scope-and-media-url.md):
category nhiều cấp giữ đúng `path`; thuộc tính `SELECT` có `value_label`, kiểu khác
`value_label = null`; trục biến thể và option giữ nguyên thứ tự Product trả; media cấp
Product và cấp SKU map đúng `sku_id`; `url`/`thumbnail_url` dựng đúng từ CDN base URL và
percent-encode storage key; SKU không ảnh nhận URL placeholder; không field storage key nào
lọt ra public response; media trỏ SKU không có, variant value không có option, trùng
`sku_id`/`media_id` đều trả `503` và ghi `contract_violation`; thiếu CDN base URL hoặc URL
placeholder thì BFF không ready.

Test resilience theo [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md)
và ADR-007: chèn độ trễ trước pha Product để deadline còn dưới 100 ms thì trả `503` mà
không gọi Product; chèn độ trễ để sau pha Product còn dưới 100 ms thì không gọi
Inventory/Review (Product chậm một mình không đạt được điều kiện này vì Product bị cắt tối
đa 400 ms); timeout Product co lại theo deadline còn lại; timeout BFF
kích hoạt trước timeout mesh; không có retry ở ứng dụng cho cả ba dependency; circuit
breaker mở, half-open rồi đóng cho từng dependency, và circuit Review mở không ảnh hưởng
Product/Inventory; Product circuit open trả `503` kèm `Retry-After`; bulkhead từ chối
theo đúng mapping và không tính vào circuit breaker; `400`/`401`/`403` từ từng dependency
được map đúng, phát alert và không tính vào circuit breaker; client hủy request thì mọi
call con bị hủy; mọi response degrade, `404` và `503` mang `Cache-Control: no-store`.

## Definition of Done

Public OpenAPI và cả ba internal dependency contract có contract
test; BFF gửi đúng một Inventory batch call sau khi Product trả SKU; mTLS/service
identity, Review bearer token, `traceparent`, request ID đã chuẩn hóa (`X-Request-Id` cho
Product/Review, `X-Correlation-ID` cho Inventory) được truyền; Inventory failure map đúng `UNKNOWN`; Review failure map đúng `UNAVAILABLE`;
`404`/`503` là `application/problem+json`; public response không chứa storage key;
observability/circuit breaker/resilience test pass; load test (gồm Product 500 SKU, 5 020
media) xác nhận SLO và latency kỳ vọng của provider ở
[operations.md](./operations.md#slo); và không có database migration, Kafka consumer,
export/bootstrap client, Review table/consumer/command handler hoặc Search dependency
trong BFF.
