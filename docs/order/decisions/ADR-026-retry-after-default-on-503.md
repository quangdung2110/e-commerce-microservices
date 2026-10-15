---
doc_id: order-adr-026
service: order
status: accepted
date: 2026-10-16
decision_owners:
  - Order Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md
  - java/services/order-service/src/main/java/com/ecommerce/order/web/Problems.java
  - java/services/order-service/src/test/java/com/ecommerce/order/web/ProblemsRetryAfterTest.java
---

# ADR-026 — `Retry-After` mặc định cho `503` retryable

## Status

`Accepted`. Áp dụng [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Bổ sung ADR-024.

## Context

`Problems.response` chỉ đặt `Retry-After` khi nơi ném lỗi truyền `retryAfterSeconds`. `ORDER_UNAVAILABLE`, `DEPENDENCY_UNAVAILABLE` và `SUBMISSION_IN_PROGRESS` (`503`, `retryable`) có thể ra mà không có header, trái [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md).

## Decision

`Problems.defaultRetryAfter(code)`: `429` → `1`, `503` có `retryable` → `2`, còn lại không có. Giá trị của nơi ném lỗi, nếu có, thắng mặc định. Không đổi mã lỗi, body hay hành vi saga.

## Alternatives considered

- **Đặt giá trị ở từng nơi ném `ApiException`.** Không chọn: dễ sót; lớp dựng problem là chỗ duy nhất mọi lỗi đi qua.

## Consequences

Mọi `503` retryable của Order (Checkout gọi Order, admin) có `Retry-After`. Checkout đã đọc header này.

## Migration impact

Code: `Problems`.

## Verification

`ProblemsRetryAfterTest`: mọi `ErrorCode` `503` retryable có `Retry-After: 2`, giá trị tường minh thắng, mã khác không có header.
