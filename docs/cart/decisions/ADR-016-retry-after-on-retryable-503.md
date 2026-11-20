---
doc_id: cart-adr-016
service: cart
status: accepted
date: 2026-10-15
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md
  - go/services/cart-service/internal/api/middleware/middleware.go
  - go/services/cart-service/internal/api/middleware/retry_after_test.go
  - contracts/http/cart/v1/openapi.yaml
---

# ADR-016 — `Retry-After` trên `503` retryable

## Status

`Accepted`. Áp dụng [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Không đổi ADR nào của Cart.

## Context

Cart gửi `Retry-After` cho `429` (rate limit theo user) nhưng không cho `503` (`CART_STORE_UNAVAILABLE`, `PRODUCT_UNAVAILABLE`), dù hai mã đó luôn `retryable=true`. Frontend và Checkout phải tự đoán khoảng chờ.

## Decision

1. `AbortWithProblem` đặt `Retry-After: 2` cho mọi problem `503` có `retryable=true` khi handler chưa đặt giá trị; `429` tiếp tục đặt giá trị của rate limiter.
2. Một `503` không retry được (xem ADR-017: Product từ chối vì lỗi của Cart) không có header.
3. Contract khai báo header trên `CartStoreUnavailable` và `WriteDependencyUnavailable`.

## Alternatives considered

- **Đặt header ở từng handler.** Không chọn: mọi lỗi đi qua `AbortWithProblem`, một chỗ là đủ.
- **Giá trị theo trạng thái breaker.** Không chọn: breaker nằm trong client Product, handler không biết; 2 giây đủ để breaker half-open thử lại.

## Consequences

Không đổi mã lỗi hay body. Caller đọc được khoảng chờ; không có retry mới ở Cart.

## Migration impact

Code: `middleware.go`. Contract: `contracts/http/cart/v1/openapi.yaml`. Docs: `interfaces.md` §2.1.

## Verification

`retry_after_test.go`: `503` retryable có `Retry-After: 2`; `422` không có.
