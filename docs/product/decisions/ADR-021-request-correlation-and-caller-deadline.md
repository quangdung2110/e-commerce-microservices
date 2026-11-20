---
doc_id: product-adr-021
service: product
status: accepted
date: 2026-10-15
decision_owners:
  - Product Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-002-correlation-and-trace-propagation.md
  - contracts/decisions/ADR-003-deadline-propagation.md
  - java/services/product-service/src/main/java/com/platform/productservice/common/RequestCorrelation.java
  - java/services/product-service/src/main/java/com/platform/productservice/config/RequestCorrelationFilter.java
  - java/services/product-service/src/main/java/com/platform/productservice/infrastructure/grpc/ProductRpcTiming.java
  - java/services/product-service/src/test/java/com/platform/productservice/config/RequestCorrelationFilterTest.java
  - java/services/product-service/src/test/java/com/platform/productservice/infrastructure/grpc/ProductRpcTimingTest.java
---

# ADR-021 — Request ID, trace ID và deadline của caller trong log của Product

## Status

`Accepted`. Áp dụng [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md)
và [ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) cho bên nhận là Product. Không đổi
ADR nào của Product.

## Context

- `ProductRpcTiming` (gRPC `GetBatchSkus`) chỉ nhận `X-Request-Id` khớp `[a-zA-Z0-9_-]{1,128}`. Request ID
  của Checkout theo `^[A-Za-z0-9._:-]{8,128}$`, nên mọi ID có `.` hoặc `:` bị ghi là `-`.
- Route HTTP `/internal/v1/**` (Search, Promotion, PDP BFF gọi) không ghi `request_id`/`trace_id`, và
  `logging.pattern.console` không có MDC.
- Product không biết caller chờ bao lâu: Cart và Checkout đặt deadline gRPC, các caller HTTP sắp gửi
  `X-Request-Timeout-Ms`.

## Decision

1. Thêm `RequestCorrelationFilter` (servlet, chạy trước `TrustedIdentityFilter`): chuẩn hoá `X-Request-Id`
   (sai hoặc thiếu thì lấy trace ID của `traceparent`, rồi UUID; không bao giờ `400`), đưa `request_id` và
   `trace_id` vào MDC, trả `X-Request-Id`. Header `Authorization`, body và đường dẫn không bị đụng tới.
2. `logging.pattern.console` thêm `[%X{request_id:-},%X{trace_id:-}]`.
3. `ProductRpcTiming` dùng đúng định dạng ADR-002 (`^[A-Za-z0-9._:-]{8,128}$`). Giá trị sai vẫn ghi `-` và
   không từ chối RPC. Chỉ `GetBatchSkus` được ghi log timing như trước.
4. `X-Request-Timeout-Ms`: request HTTP vượt giá trị đó được đếm
   (`http.server.caller_deadline_exceeded{route}`) và ghi WARN. Product không hủy công việc. Deadline gRPC
   dùng cơ chế gốc của grpc-java, không cần xử lý thêm.

## Alternatives considered

- **Chỉ sửa regex của gRPC.** Không chọn: route HTTP vẫn không có ID trong log.
- **Dùng thư viện tracing của Micrometer.** Không chọn trong đợt này: ADR-002 chỉ yêu cầu mức 1
  (pass-through); mức 2 là lộ trình riêng.

## Consequences

Log của Product tra chéo được với Cart, Checkout, Search, Promotion và PDP BFF bằng `request_id`. Thêm một
counter có nhãn `route` là route template (số lượng hữu hạn).

## Migration impact

Code: `RequestCorrelation`, `RequestCorrelationFilter` (mới), `ProductRpcTiming`. Config:
`application.yml` (`logging.pattern.console`). Không đổi contract.

## Verification

`RequestCorrelationTest`, `RequestCorrelationFilterTest` (MDC, header trả về, giá trị sai, counter vượt
deadline), `ProductRpcTimingTest` (ID có `.`/`:` được giữ, ID sai ghi `-`).
