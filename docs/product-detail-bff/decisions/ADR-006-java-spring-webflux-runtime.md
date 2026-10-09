---
doc_id: product-detail-bff-adr-006
service: product-detail-bff
status: accepted
date: 2026-09-25
decision_owners:
  - Storefront team
supersedes: null
amends: null
evidence:
  - docs/product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md
  - docs/product-detail-bff/operations.md
  - contracts/catalog/services.yaml
---

# ADR-006 — Java 21, Spring Boot WebFlux và Resilience4j cho BFF

## Status

`Accepted`. Không supersede hay amend ADR nào.

## Context

`contracts/catalog/services.yaml` còn ghi `runtime: undecided`. Repo có hai stack: Go (Auth,
User, Cart, Notification, gateway) và Java 21 + Spring Boot 3.3 (Product, Review, Search,
Promotion). BFF là service không state, chỉ làm fan-out I/O, nhưng
[ADR-004](./ADR-004-resilience-budget-and-mesh-split.md) đặt yêu cầu resilience khá chi
tiết:

- circuit breaker cửa sổ trượt theo **thời gian** 10 giây, tối thiểu 20 call, mở theo tỷ lệ
  lỗi **hoặc** tỷ lệ call chậm, half-open 5 call;
- bulkhead semaphore không hàng chờ theo dependency;
- timeout theo deadline còn lại, không retry ở ứng dụng;
- khi client hoặc gateway hủy request, mọi call con phải bị hủy.

## Decision

- **Ngôn ngữ và framework:** Java 21, Spring Boot 3.3.x (cùng phiên bản Product/Review),
  **Spring WebFlux trên Reactor Netty**. Module tại `java/services/product-detail-bff`,
  package gốc `com.ecommerce.pdp`, build bằng Maven như các service Java khác.
- **HTTP client:** một `WebClient` cho mỗi dependency, mỗi cái có `ConnectionProvider` riêng
  với `maxConnections` không nhỏ hơn giới hạn bulkhead của dependency đó.
- **Resilience4j** (`resilience4j-spring-boot3`, `resilience4j-reactor`), map trực tiếp
  ADR-004: `CircuitBreaker` với `slidingWindowType=TIME_BASED`, `slidingWindowSize=10`,
  `minimumNumberOfCalls=20`, `failureRateThreshold=50`, `slowCallRateThreshold=50`,
  `slowCallDurationThreshold` 320 ms (Product) / 240 ms (Inventory, Review),
  `waitDurationInOpenState=10s`, `permittedNumberOfCallsInHalfOpenState=5`; predicate ghi
  lỗi theo bảng phân loại của ADR-004 (bỏ qua `400`/`401`/`403` — status ngoài contract khác vẫn là lỗi, ADR-008 —, bulkhead từ chối, bỏ vì budget, hủy do client). `Bulkhead` semaphore với `maxWaitDuration=0`. Không dùng module `Retry`.
- **Deadline và hủy:** deadline tuyệt đối đặt vào Reactor `Context` khi nhận request;
  timeout mỗi call là `Mono.timeout` tính từ deadline còn lại; pha 2 dùng `Mono.zip` hai
  nhánh đã có fallback. Khi client ngắt, subscription bị hủy và Reactor Netty hủy các
  request con đang chạy, đúng yêu cầu ADR-004 mà không cần code riêng.
- **Đọc response dependency (tolerant reader):** field không biết bị bỏ qua để provider
  thêm field không làm PDP `503`. Vi phạm schema là thiếu field bắt buộc, sai kiểu, sai enum
  hoặc vượt giới hạn, cộng các ràng buộc tham chiếu ở
  [ADR-005](./ADR-005-pdp-catalog-scope-and-media-url.md).
- **Observability:** Actuator, Micrometer + Prometheus, Micrometer Tracing bridge
  OpenTelemetry để truyền `traceparent`, log JSON bằng `logstash-logback-encoder` như
  Review.
- **Test:** JUnit 5, WireMock giả lập ba dependency, `StepVerifier`/virtual time cho
  deadline và circuit breaker, validate public response theo OpenAPI trong contract test.

## Alternatives considered

- **Go.** Nhẹ, `context` có sẵn deadline và cancel. Nhưng thư viện circuit breaker phổ biến
  (gobreaker, failsafe-go) không có tỷ lệ call chậm trên cửa sổ thời gian như ADR-004 yêu
  cầu; phải tự viết phần dễ sai nhất của service hoặc nới ADR-004.
- **Spring MVC + virtual threads.** Code tuần tự dễ đọc hơn, nhưng servlet container chỉ
  phát hiện client ngắt khi ghi response, nên không hủy được call con đang chạy như ADR-004
  yêu cầu nếu không tự viết thêm cơ chế async listener.
- **Kotlin coroutines trên WebFlux.** Hủy tốt và code gọn, nhưng thêm ngôn ngữ thứ ba vào
  repo chỉ cho một service.

## Consequences

Cấu hình resilience của ADR-004 viết được gần như nguyên văn. Đổi lại đội phải quen code
reactive (Reactor), khó debug stack trace hơn MVC, và footprint JVM lớn hơn Go. Không được
gọi blocking I/O trong pipeline; BlockHound trong test dùng để chặn việc này.
`contracts/catalog/services.yaml` ghi `runtime: java`.

## Verification

- Test circuit breaker mở theo tỷ lệ call chậm với cửa sổ thời gian, half-open 5 call, và
  không đếm `4xx`/bulkhead/budget/hủy.
- Test hủy: client ngắt khi pha 2 đang chạy thì WireMock ghi nhận request bị hủy.
- Test tolerant reader: field thừa từ dependency không làm `503`; thiếu field bắt buộc thì
  `503`/degrade đúng dependency.
- BlockHound không phát hiện call blocking trong pipeline request.
