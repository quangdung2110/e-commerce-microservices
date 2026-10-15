---
doc_id: inventory-adr-031
service: inventory
status: accepted
date: 2026-10-16
decision_owners:
  - Inventory Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md
  - java/services/inventory-service/src/main/java/com/ecommerce/inventory/web/Problems.java
  - java/services/inventory-service/src/test/java/com/ecommerce/inventory/web/ProblemsRetryAfterTest.java
---

# ADR-031 — `Retry-After` mặc định cho `503` retryable

## Status

`Accepted`. Áp dụng [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md).

## Context

`Problems.response` chỉ đặt `Retry-After` khi nơi ném lỗi truyền `retryAfterSeconds`; `INVENTORY_UNAVAILABLE`, `INVENTORY_BUSY` và `INVENTORY_AVAILABILITY_UNAVAILABLE` (`503`, `retryable`) có thể ra mà không có header, trái [contracts ADR-005](../../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md).

## Decision

`Problems.defaultRetryAfter(code)`: `429` → `1`, `503` có `retryable` → `2`; giá trị của nơi ném lỗi thắng mặc định. Mã lỗi, body và hành vi không đổi.

## Alternatives considered

- **Đặt ở từng nơi ném `ApiException`.** Không chọn: xem Order ADR-026.

## Consequences

Mọi `503` retryable của Inventory (Order, Checkout, PDP, Search gọi) có `Retry-After`. Order `HttpRemote` ghi header vào log.

## Migration impact

Code: `Problems`.

## Verification

`ProblemsRetryAfterTest` như Order.
