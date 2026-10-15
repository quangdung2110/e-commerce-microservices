---
doc_id: checkout-adr-019
service: checkout
status: accepted
date: 2026-10-15
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-006-grpc-error-details.md
  - java/services/checkout-service/src/main/java/com/ecommerce/checkout/client/ProductClient.java
  - java/services/checkout-service/src/main/java/com/ecommerce/checkout/resilience/DependencyGuard.java
  - java/services/checkout-service/src/test/java/com/ecommerce/checkout/client/ProductClientErrorReasonTest.java
---

# ADR-019 — Ghi `reason` của lỗi gRPC Product

## Status

`Accepted`. Áp dụng [contracts ADR-006](../../../contracts/decisions/ADR-006-grpc-error-details.md). Bổ sung ADR-014 (ngân sách resilience).

## Context

`ProductClient.translate` phân loại lỗi gRPC theo mã status, nên log chỉ nói `403` mà không nói là danh tính bị từ chối hay lý do khác. Product nay gửi `ErrorInfo.reason` ([contracts ADR-006](../../../contracts/decisions/ADR-006-grpc-error-details.md)).

## Decision

`translate` đọc `ErrorInfo.reason` (domain `ecommerce.product.v1`) và truyền vào `UpstreamStatusException.code`; `DependencyGuard` ghi nó trong log lỗi cấu hình. Ánh xạ status sang lỗi nội bộ không đổi (`INVALID_ARGUMENT` 400, `UNAUTHENTICATED` 401, `PERMISSION_DENIED` 403, `UNAVAILABLE` → I/O, `DEADLINE_EXCEEDED` → timeout, còn lại 503). Status không có chi tiết (runtime, mesh) vẫn phân loại theo mã.

## Alternatives considered

- **Đổi ánh xạ theo `retryable`.** Không chọn: Checkout không retry Product ở ứng dụng, và các mã đã tách đúng nhóm client/server cho breaker.

## Consequences

Chỉ log và `code` của exception thay đổi; không có thay đổi hành vi hay contract.

## Migration impact

Code: `ProductClient.reasonOf`, `DependencyGuard` log.

## Verification

`ProductClientErrorReasonTest`: từng status có reason, reason của domain khác bị bỏ qua, status không chi tiết map theo mã, lỗi không phải gRPC đi qua.
