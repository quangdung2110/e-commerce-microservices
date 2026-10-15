---
doc_id: payment-adr-027
service: payment
status: accepted
date: 2026-10-15
decision_owners:
  - Payment Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md
  - java/services/payment-service/src/main/java/com/platform/paymentservice/adapter/in/http/RetryAfterHeader.java
  - java/services/payment-service/src/main/java/com/platform/paymentservice/adapter/in/webhook/StripeWebhookController.java
  - contracts/http/payment/v1/openapi.yaml
  - contracts/http/payment/internal/v1/openapi.yaml
---

# ADR-027 — `Retry-After` trên `503` retryable, kể cả webhook

## Status

`Accepted`. Áp dụng [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Không đổi ADR nào của Payment.

## Context

Payment trả `503` với `retryable=true` (`DEPENDENCY_UNAVAILABLE`, `PROVIDER_OUTCOME_UNKNOWN`, `PROVIDER_UNAVAILABLE`) và một `503` `retryable=false` (`PROVIDER_REQUEST_REJECTED`). Không có `Retry-After` nào, và contract dùng chung một response `Problem` cho mọi mã lỗi nên không thể khai báo header chỉ cho `503`.

## Decision

1. `RetryAfterHeader.apply(headers, status, retryable)`: `503` retryable → `Retry-After: 2`; `429` → `1`; mọi trường hợp khác không có. Bốn nơi dựng problem (`PaymentController`, `PaymentQueryController`, `PaymentOperationsController`, `PaymentHttpExceptionHandler`) gọi nó.
2. `PROVIDER_REQUEST_REJECTED` không có header: nó là kết quả đã chốt, gọi lại không đổi gì.
3. Webhook Stripe `503` cũng có `Retry-After: 2` (thông tin, Stripe tự lên lịch).
4. Cả hai OpenAPI tách response `Unavailable` (có header) khỏi `Problem` cho `503`.

## Alternatives considered

- **Header ở mọi `Problem`.** Không chọn: `503` không retry được sẽ nói dối.
- **Giá trị theo loại lỗi.** Không chọn: 2 giây là ước lượng chung; Order không đổi hành vi retry theo nó (saga sở hữu retry).

## Consequences

Order `HttpRemote` đã log `Retry-After` nên khoảng chờ hiện trong log; mã lỗi và body không đổi.

## Migration impact

Code: bốn nơi dựng problem, webhook controller, `RetryAfterHeader`. Contract: hai OpenAPI. Docs: `interfaces.md` §2.3.

## Verification

`RetryAfterHeaderTest`, `PaymentQueryControllerTest.a_database_failure_is_a_retryable_503_with_retry_after`, `StripeWebhookControllerTest` (header trên `503`).
