---
doc_id: search-adr-025
service: search
status: accepted
date: 2026-10-15
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md
  - java/services/search-service/src/main/java/com/ecommerce/search/controller/ProblemExceptionHandler.java
  - java/services/search-service/src/test/java/com/ecommerce/search/controller/ProblemExceptionHandlerRetryAfterTest.java
  - contracts/http/search/v2/openapi.yaml
---

# ADR-025 — `Retry-After` trên `ELASTICSEARCH_UNAVAILABLE`

## Status

`Accepted`. Áp dụng [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Bổ sung ADR-024.

## Context

`ELASTICSEARCH_UNAVAILABLE` (`503`, `retryable=true`) là lỗi retryable duy nhất của Search nhưng không có `Retry-After`; frontend không biết bao lâu thì thử lại.

## Decision

`ProblemExceptionHandler.problem(...)` thêm `Retry-After: 2` cho mọi `503` có `code.retryable()`. `SEARCH_TIMEOUT` (`504`) và `INTERNAL_ERROR` không có. Contract khai báo header ở `ElasticsearchUnavailable`.

## Alternatives considered

- **Đặt cho `504` nữa.** Không chọn: ADR-005 chỉ áp cho `429`/`503`.

## Consequences

Không đổi mã lỗi hay body.

## Migration impact

Code: `ProblemExceptionHandler`. Contract: `search/v2/openapi.yaml`. Docs: `interfaces.md` §error.

## Verification

`ProblemExceptionHandlerRetryAfterTest`: cluster không tới được → `503` + `Retry-After: 2`; timeout và lỗi bất ngờ không có header.
