---
doc_id: promotion-adr-022
service: promotion
status: accepted
date: 2026-10-15
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-002-correlation-and-trace-propagation.md
  - contracts/decisions/ADR-003-deadline-propagation.md
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/common/RequestCorrelation.java
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/config/RequestCorrelationFilter.java
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/infrastructure/product/HttpProductCatalogClient.java
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/config/RequestCorrelationFilterTest.java
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/infrastructure/product/HttpProductCatalogClientHeadersTest.java
---

# ADR-022 — Request ID, trace ID và deadline của caller trong Promotion

## Status

`Accepted`. Áp dụng [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md)
và [ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) cho Promotion, vừa là bên nhận
(Checkout, Order) vừa là bên gọi (Product snapshot). Không đổi ADR nào của Promotion.

## Context

- `TrustedIdentityFilter` lưu `X-Request-Id` và `traceparent` thô vào `CallerContext` cho `trace_id` của
  problem; không có chuẩn hoá và không có MDC, nên log không có request ID.
- Checkout đã gửi `X-Request-Id`, còn Order chưa gửi gì cho Promotion cho tới nay (ADR-002 sửa việc này).
- `HttpProductCatalogClient` (validate sản phẩm khi lưu markdown rule) không gửi request ID, trace context
  hay timeout cho Product, trong khi Product ghi log theo `request_id`.

## Decision

1. Thêm `RequestCorrelationFilter` chạy trước `TrustedIdentityFilter`: chuẩn hoá `X-Request-Id` (không bao
   giờ `400`), đưa `request_id`/`trace_id` vào MDC, trả `X-Request-Id`.
2. `CallerContext` giữ nguyên: `trace_id` của problem vẫn lấy từ header thô. Hai giá trị trùng nhau khi
   header đúng định dạng; khi sai, log dùng giá trị đã chuẩn hoá còn problem giữ hành vi cũ.
3. `logging.pattern.console` thêm `[%X{request_id:-},%X{trace_id:-}]`.
4. Request vượt `X-Request-Timeout-Ms` được đếm (`http.server.caller_deadline_exceeded{route}`) và ghi
   WARN; Promotion không hủy công việc. `reserve`, `cancel`, `confirm` là lệnh ghi có idempotency và
   Order sở hữu retry, nên không bao giờ bị bỏ dở vì header (ADR-003 4.4).
5. Khi bên gọi: `HttpProductCatalogClient` gửi `X-Request-Id` của request đang phục vụ (UUID mới nếu không
   có), `traceparent`/`tracestate` nếu có, và `X-Request-Timeout-Ms` bằng `promotion.product.timeout`
   (2 giây, nhỏ hơn timeout mesh 3 giây của route snapshot). Request admin đến từ Gateway nên không mang
   deadline, đúng như Gateway xoá `X-Request-Timeout-Ms`.

## Alternatives considered

- **Cho `CallerContext` dùng giá trị đã chuẩn hoá.** Không chọn: đổi giá trị `trace_id` trong problem là
  thay đổi contract nhìn thấy được; làm riêng nếu cần.
- **Gửi `X-Correlation-Id` tới Product.** Không chọn: contract Product không định nghĩa header này.

## Consequences

Log Promotion tra chéo được với Checkout, Order và Product. Một lời gọi Product chậm trong request admin có
`request_id` ở cả hai phía.

## Migration impact

Code: `RequestCorrelation`, `RequestCorrelationFilter` (mới), `HttpProductCatalogClient`. Config:
`application.yml` (`logging.pattern.console`). Không đổi contract.

## Verification

`RequestCorrelationTest`, `RequestCorrelationFilterTest`, `TrustedIdentityFilterTest` (không đổi),
`HttpProductCatalogClientHeadersTest` (request ID, trace context và `X-Request-Timeout-Ms` tới Product).
