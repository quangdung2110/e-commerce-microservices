---
doc_id: product-detail-bff-development
service: product-detail-bff
scope: current
status: implemented
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

Response dependency đọc theo tolerant reader: bỏ qua field không biết; thiếu field bắt buộc, sai kiểu/enum/giới hạn (kể cả độ dài chuỗi) hoặc vi phạm ràng buộc tham chiếu mới là sai contract. Field bắt buộc nhưng nullable (`description`, `alt_text`, `value_label`, `thumbnail_storage_key`...) mà vắng mặt được đọc như `null`.

## Chạy local

Máy dev không cần JDK: build bằng container `maven:3.9-eclipse-temurin-21` như các service
Java khác. `docker-compose.yml` có service `product-detail-bff` (host port `8093`) và route
qua gateway tại `http://localhost:8088/api/v1/storefront/products/{slug}`:

| Thành phần local | Giá trị |
|---|---|
| Inventory | `PDP_INVENTORY_ENABLED=false` dù `inventory-service` đã chạy trong compose, vì cờ chỉ bật sau rollout gate ([README](./README.md#trạng-thái-rollout)): mọi SKU `UNKNOWN` với outcome `disabled`. Đặt `PDP_INVENTORY_ENABLED=true` và `PDP_INVENTORY_BASE_URL=http://inventory-service:8080` để thử availability thật |
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

Product nhiều SKU với trạng thái Inventory mixed; Product đủ 500 SKU vẫn chỉ phát đúng một Inventory batch;
Inventory thiếu một item và trả item cho SKU không yêu cầu (SKU khác giữ kết quả, counter `pdp_inventory_item_violations_total` tăng và có dòng log kèm `request_id`); Inventory trả `UNKNOWN`; Inventory timeout/5xx/circuit
open; Product trả `404` (gồm Product `HIDDEN`) thì `404` kèm `no-store`; Product timeout;
slug sai pattern hoặc dài hơn 180 trả `404` `no-store` mà không gọi Product;
Review có aggregate 0; Review có
preview; Review timeout/5xx; và response Inventory/Review/Product sai contract (gồm
Inventory trùng `sku_id`); field thừa trong response dependency không làm lỗi. Kịch bản Order Reserve thất bại sau khi PDP đã hiện `IN_STOCK` là test liên service của Checkout/Order, không thuộc bộ test của BFF vì BFF không bao giờ gọi Reserve.

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
`sku_id`/`media_id`, storage key hay thumbnail key có segment `.`/`..`/rỗng và chuỗi vượt giới hạn contract Product đều trả `503` và ghi `contract_violation`; thiếu CDN base URL hoặc URL placeholder thì BFF không ready.

Test biên và status theo [ADR-008](./decisions/ADR-008-post-implementation-audit-corrections.md): status ngoài contract (`429`, `3xx`, `404` của Inventory/Review, `404` không phải của Product) tính vào circuit breaker còn `400`/`401`/`403` thì không, với cả ba dependency; readiness `UP` khi cả ba circuit đang mở và về `OUT_OF_SERVICE` khi graceful shutdown bắt đầu; đường dẫn rỗng, có `/` cuối hoặc nhiều segment dưới prefix PDP trả `404` problem `no-store` mà không gọi dependency; dòng log cảnh báo dependency và cảnh báo item Inventory mang `request_id`/`trace_id`.

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
