---
doc_id: checkout-development
service: checkout-service
scope: current
status: implemented
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/catalog/interactions.yaml
verified_against_commit: null
---

# Checkout Service — Development Guide

Checkout cần client cho Cart, User, Product (gRPC), Inventory, Promotion, Order và Redis.
Không cần PostgreSQL, Kafka, Temporal hay quyền truy cập database của service khác.

## Stack và cấu trúc

Theo [ADR-013](./decisions/ADR-013-java-spring-webflux-runtime.md): Java 21, Spring Boot
3.3.x WebFlux trên Reactor Netty, `WebClient` riêng cho mỗi HTTP dependency, grpc-java với
stub non-blocking cho Product, Lettuce reactive cho Redis (`EVALSHA`), Resilience4j
(`CircuitBreaker`, `Bulkhead` semaphore; timeout bằng `Mono.timeout` của Reactor, không dùng
`TimeLimiter`; không `Retry`), Micrometer + Prometheus, Micrometer Tracing bridge OpenTelemetry
(span nghiệp vụ do `Spans` tạo, xem [operations.md](./operations.md#span)). Module `java/services/checkout-service`,
package gốc `com.ecommerce.checkout`. Pipeline request không được gọi blocking I/O;
BlockHound chạy trong test.

Response dependency đọc theo tolerant reader: bỏ qua field không biết; thiếu field bắt
buộc, sai kiểu/enum hoặc vi phạm ràng buộc ở [interfaces.md](./interfaces.md) mới là sai
contract. Enum chưa biết của Promotion được map về `UNKNOWN` hoặc bị bỏ như interfaces mô tả.

## Thứ tự triển khai

1. Product stub sinh từ proto (`proto/` trong module, có test chống lệch với `contracts/`); DTO
   HTTP là record viết tay theo tolerant reader, được kiểm với OpenAPI bằng contract test
   ([ADR-018](./decisions/ADR-018-implementation-decisions.md)). Không có DTO nào khác contract.
2. Crypto: HMAC token lookup, envelope encryption có version, xoay khoá.
3. Redis repository và Lua scripts (Preview, claim, recovery claim, finalize).
4. Dependency clients với timeout/circuit breaker/bulkhead/header propagation.
5. Preview orchestrator, media URL builder, tính tiền integer VND.
6. Submit, recovery, mapping kết quả Order.
7. Voucher options.
8. Telemetry, readiness, graceful shutdown; route Gateway sau smoke test.

## Chạy local

Máy dev không cần JDK: build bằng container `maven:3.9-eclipse-temurin-21` như các service
Java khác. `docker-compose.yml` khai báo `checkout-service` (host port `8096`, network
`ecommerce-network`) cùng `checkout-redis` riêng với `noeviction`, và bốn router Gateway kèm ba
profile rate limit `checkout-*`.

```bash
# build + toàn bộ test (cần Docker socket cho Redis Testcontainers; mount repo root để đọc contracts/)
MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v "//c/Users/ASUS/MyProjects/e-commerce-microservices://repo" -v checkout-mvn-repo://root/.m2 \
  --add-host=host.docker.internal:host-gateway -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w //repo/java/services/checkout-service maven:3.9-eclipse-temurin-21 \
  mvn -B -Dos.detected.classifier=linux-x86_64 test
```

| Thành phần local | Giá trị |
|---|---|
| Cart | `http://cart-service:8080` |
| User | `http://user-service:8083` (cổng internal, không qua Traefik) |
| Product gRPC | `product-service:9091` |
| Inventory | `http://inventory-service:8080` |
| Promotion | `http://promotion-service:8085`; `CHECKOUT_VOUCHER_OPTIONS_ENABLED=true` (Promotion đã có endpoint) |
| Order | `http://order-service:8080` |
| Media | `CHECKOUT_MEDIA_CDN_BASE_URL=http://localhost:8088/media`, placeholder `http://localhost:8088/media/placeholder/product.svg`, `CHECKOUT_MEDIA_REQUIRE_HTTPS=false` (giống PDP) |

Local không có mesh: Checkout vẫn gửi `X-Service-Identity: checkout-service`, và các provider
kiểm allowlist header này.

Integration test không phụ thuộc service thật: WireMock giả lập các HTTP dependency, gRPC
in-process server giả lập Product, Testcontainers Redis chạy Lua thật.

## Test bắt buộc

**Tính tiền và view model:** rounding integer VND; `final_amount = total − discount`;
`final_amount < 1` → `FINAL_AMOUNT_NOT_PAYABLE`; `image_url` dựng đúng từ storage key có ký
tự đặc biệt; key rỗng → placeholder trong báo giá nhưng `snapshot_image_url = null` sang
Order; response không chứa `warehouse_id`, `category_ids`, hash.

**Preview:** giỏ rỗng; SKU not found/inactive/`product_id` lệch → `SKU_UNAVAILABLE` kèm
`sku_ids`; address `404`; `REJECT_ALL` → `INSUFFICIENT_STOCK` kèm `rejected_lines`; Promotion
`eligible=false` mọi `reason`; Promotion `subtotal` lệch → `503`; plan Inventory thiếu/thừa
dòng → `503`; không có `promotion_id` thì không gọi Promotion; Inventory và Promotion chạy
song song (trace).

**Cạnh tranh (Redis thật):** fresh preview thay `PREVIEWED` cũ; `previous` cũ →
`ACTIVE_CHECKOUT_CHANGED`; hai Preview đồng thời chỉ một token `PREVIEWED`; Preview khi
`SUBMITTING`/`RETRYABLE` → `SUBMISSION_IN_PROGRESS` và không fan-out; replay idempotency cùng
body/khác body; hai Submit đồng thời chỉ một claim; Preview lỗi không đổi state.

**Submit và recovery:** revalidation từng loại thay đổi và thứ tự ưu tiên code +
`changed_fields`; revalidation lỗi hạ tầng → `RETRYABLE` rồi Submit lại revalidate trước khi
gọi Order; Order success/`409` mỗi code/`422`/`400`/`401`/`403`/`503`/timeout trước và sau khi
Order ghi; recovery tìm thấy đơn sau khi mất response; Order `404` khi còn lời gọi có thể đang
bay → `202`, trong cửa sổ → re-POST cùng payload/key, quá cửa sổ → `SUBMISSION_NOT_ACCEPTED`;
replay `SUCCEEDED` lấy Payment data mới, `usable=false` trả secret `null`; client ngắt kết nối
sau claim vẫn ghi kết quả.

**Voucher options:** context dựng từ session (không gọi Cart/Product); mapping
`required_action` mọi tổ hợp; enum chưa biết bị bỏ và đếm metric; token không `PREVIEWED` →
`409`/`410`; Promotion lỗi → `503`, Preview vẫn chạy; cờ tắt không gọi Promotion.

**Resilience:** timeout Checkout kích hoạt trước timeout mesh; deadline còn < 200 ms trước
wave → `503` không gọi; Order không được gọi khi deadline còn < 2 000 ms; không retry ứng
dụng; circuit từng dependency mở/half-open/đóng độc lập; bulkhead từ chối không tính vào
circuit; `400`/`401`/`403` không tính vào circuit và phát alert.

**Header và bảo mật:** `X-Request-Id` sai được thay bằng trace ID và không tới Inventory/Order;
Inventory nhận `X-Correlation-ID`, `X-Causation-ID`, `X-Service-Identity`; Order nhận
`Idempotency-Key = checkout_token`; raw token không xuất hiện trong Redis key, log, trace;
`SCAN` Redis không thấy `_secret_` hay địa chỉ plaintext; token của user khác → `404`.

**Span:** mỗi use case có đúng một span `checkout.preview` / `checkout.voucher_options` /
`checkout.submit` / `checkout.recovery` nằm dưới span server; mỗi lời gọi dependency là
`checkout.dependency` (attribute `dependency`, `outcome`) và mỗi lệnh Redis là `checkout.redis`
(attribute `operation`) ngay dưới use case; span client HTTP nằm dưới `checkout.dependency`; phần
việc Submit chạy tách khỏi request vẫn thuộc `checkout.submit`; problem nghiệp vụ không đánh
dấu lỗi, problem `5xx` thì có.

**Route không khớp:** route hoặc method không có vẫn là `CheckoutProblem` (`INVALID_REQUEST`) với
đúng HTTP status của framework (`404`, `405`) và `Cache-Control: private, no-store`; body không
phải JSON là `400`.

### Bản đồ test (module `java/services/checkout-service`)

| Nhóm trong danh sách trên | Test |
|---|---|
| Tính tiền, view model, hash theo công thức Order | `HashesTest`, `MediaUrlsTest`, `PreviewApiTest.happyPath` |
| Preview, idempotency, supersede, fence `previous`, in-flight guard | `PreviewApiTest`, `SessionStoreTest` |
| Cạnh tranh trên Redis thật (claim, recovery claim, finalize) | `SessionStoreTest`, `SubmitApiTest.concurrentSubmitsClaimOnce`, `RecoveryApiTest.concurrentRecoverers` |
| Submit, revalidation, ánh xạ kết quả Order | `SubmitApiTest` |
| Recovery và status | `RecoveryApiTest`, `ShortDeadlineSubmitTest` |
| Voucher options | `VoucherOptionsApiTest`, `VoucherOptionsDisabledTest` |
| Resilience, deadline, hủy request | `ResilienceTest`, `NonBlockingTest` (BlockHound), `SubmitApiTest.clientCancellationAfterClaimStillRecordsTheResult` |
| Header và bảo mật, không plaintext trong Redis | `PreviewApiTest`, `CryptoTest` |
| Span (tên, cây cha–con, attribute, lỗi) | `TracingSpansTest` (đọc span thật qua `SpanProcessor` của OpenTelemetry SDK, không cần exporter) |
| Route khớp theo path đã decode (`%61pi`, `;param`), route không khớp | `RequestContextFilterPathTest`, `UnroutedRequestTest` |
| Cấu hình, bất biến timeout | `CheckoutPropertiesValidationTest` |
| Contract | `ProviderFixturesContractTest`, `ProblemCatalogContractTest`, `ProtoCopyTest` |

## Contract tests

- Validate Checkout OpenAPI và example; mọi response lỗi khớp `CheckoutProblem`.
- Consumer test với Cart snapshot, User address snapshot, Product `GetBatchSkus`, Inventory
  allocation preview (`ALLOCATABLE`/`REJECT_ALL`), Promotion calculation và voucher options,
  Order POST/GET dùng fixture hash chung với Order.

## CI gates

- OpenAPI lint, protobuf breaking check, catalog validation (`contracts/tools/validate-contracts.ps1`).
- Link/frontmatter validation cho docs.
- Race/concurrency test và consumer contract test.

## Definition of Done

Bốn endpoint public và mọi internal dependency có contract test; error catalog khớp
OpenAPI; mapping kết quả Order đầy đủ; Lua scripts có test cạnh tranh trên Redis thật;
resilience test pass; load test xác nhận SLO; Gateway, mesh, catalog và frontend type được
cập nhật cùng PR; không có database migration, Kafka consumer, cache downstream hay rate
limiter riêng trong Checkout.

## Review checklist

- Không đọc storage của service khác; không thêm cache downstream khi chưa có ADR.
- Không thay `Idempotency-Key` Order (= checkout token) giữa các lần gửi.
- Không thêm degraded availability/pricing path; không tự trừ discount.
- Mọi thay đổi endpoint phải cập nhật Checkout, owner service, Gateway, Mesh và catalog trong
  cùng PR.
