---
doc_id: checkout-adr-013
service: checkout
status: accepted
date: 2026-10-07
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/development.md
  - docs/checkout/operations.md
  - contracts/catalog/services.yaml
---

# ADR-013 — Runtime Java 21 + Spring Boot WebFlux

## Status

`Accepted` 2026-10-07.

## Context

Catalog ghi Checkout `runtime: undecided`. Checkout là BFF fan-out tới sáu dependency
(HTTP và gRPC), cần deadline theo request, circuit breaker/bulkhead riêng từng dependency,
Lua script trên Redis và một phần việc phải chạy tiếp sau khi client ngắt kết nối. Repository
đã có Product Detail BFF chạy Java 21 + Spring Boot WebFlux + Resilience4j
([PDP ADR-006](../../product-detail-bff/decisions/ADR-006-java-spring-webflux-runtime.md)),
cùng toolchain build bằng container Maven, Micrometer và OpenTelemetry. Order, Promotion,
Inventory, Product cũng là Java.

## Decision

- Java 21, Spring Boot 3.3.x **WebFlux** trên Reactor Netty; module
  `java/services/checkout-service`, package gốc `com.ecommerce.checkout`.
- HTTP dependency: một `WebClient` riêng mỗi dependency (pool, timeout, header filter riêng).
- Product: grpc-java với stub non-blocking, deadline đặt theo lời gọi.
- Redis: Lettuce reactive, script nạp sẵn và gọi bằng `EVALSHA`.
- Resilience4j: `CircuitBreaker`, `Bulkhead` (semaphore), `TimeLimiter`; không dùng `Retry`.
- Micrometer + Prometheus; Micrometer Tracing bridge OpenTelemetry.
- Server stub và client model sinh từ OpenAPI/proto trong `contracts/`.
  > **Ghi chú 2026-10-07:** chỉ stub gRPC Product được sinh từ proto; DTO HTTP là record viết tay
  > theo tolerant reader và được kiểm với OpenAPI bằng contract test
  > ([ADR-018](./ADR-018-implementation-decisions.md)).
- Phần việc sau claim chạy trên scheduler riêng, không gắn với subscription của request, để
  client cancellation không hủy lời gọi Order.
- BlockHound trong test để chặn blocking I/O trên event loop.

## Alternatives considered

- **Go:** goroutine phù hợp fan-out và Cart/User đã là Go, nhưng Checkout cần cùng stack
  resilience/telemetry với PDP BFF và tái dùng kinh nghiệm Lua/Redis của Promotion (Java);
  hai BFF khác stack làm tăng chi phí vận hành. Không chọn.
- **Spring MVC + virtual threads:** code tuần tự dễ đọc, nhưng điều khiển hủy request, deadline
  lan truyền và tách phần việc sau claim khó kiểm soát hơn trên servlet; PDP đã chọn WebFlux.
  Không chọn.
- **WebFlux (chọn):** đồng nhất với PDP BFF, non-blocking tự nhiên cho fan-out, cancellation
  tường minh.

## Consequences

- Tích cực: hai BFF cùng stack, cùng thư viện resilience, cùng cách đo; tái dùng cấu hình build.
- Tiêu cực: lập trình reactive khó hơn; phải giữ kỷ luật không blocking.
- Constraint: mọi client phải truyền deadline còn lại và header chuẩn hoá.

## Migration impact

Module `java/services/checkout-service` đã có; catalog `runtime: java`, compose và router Gateway đã
thêm ([development.md](../development.md#chạy-local)).

## Verification

Module build bằng container Maven; BlockHound pass; test resilience và cancellation ở
[development.md](../development.md#test-bắt-buộc) pass.
