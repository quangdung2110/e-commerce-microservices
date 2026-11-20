---
doc_id: cart-adr-017
service: cart
status: accepted
date: 2026-10-15
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-006-grpc-error-details.md
  - go/services/cart-service/internal/infrastructure/product/errors.go
  - go/services/cart-service/internal/infrastructure/product/errors_test.go
  - go/services/cart-service/internal/infrastructure/product/client.go
  - go/services/cart-service/internal/infrastructure/observability/metrics.go
---

# ADR-017 — Phân loại lỗi Product theo `ErrorInfo`

## Status

`Accepted`. Áp dụng [contracts ADR-006](../../../contracts/decisions/ADR-006-grpc-error-details.md). Bổ sung ADR-005 (breaker và degrade của client Product).

## Context

Mọi lỗi gRPC từ Product thành `PRODUCT_UNAVAILABLE` `retryable=true` và đếm vào breaker. Khi Product từ chối vì Cart thiếu `x-service-identity` hay gửi `sku_id` sai, lỗi cấu hình trông như Product sập và breaker mở, che cả những SKU hợp lệ.

## Decision

1. `classify(err)` đọc status và `ErrorInfo` (domain `ecommerce.product.v1`): `reason`, `retryable` (mặc định theo mã khi không có chi tiết).
2. `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND` là lỗi của caller: `PRODUCT_UNAVAILABLE` với `retryable=false`, **không** đếm vào breaker (Product vẫn trả lời đúng; `breaker.success()`).
3. Lỗi còn lại đếm vào breaker; `retryable` theo `ErrorInfo` nếu có (`INTERNAL_ERROR` là false), ngược lại true.
4. Lý do vào thông điệp lỗi (`reason=...`) để log; metric `cart_product_calls_total` thêm outcome `rejected` và label `reason`.

## Alternatives considered

- **Giữ mọi lỗi retryable.** Không chọn: lỗi cấu hình bị retry vô ích và làm breaker mở.
- **Mở breaker cho cả lỗi caller.** Không chọn: nó chứng tỏ Product khoẻ.

## Consequences

Cart ghi `503` `PRODUCT_UNAVAILABLE` `retryable=false` (không `Retry-After`, theo ADR-016) khi Product từ chối. Dashboard cũ nhóm theo `rpc, outcome` vẫn đúng; thêm label `reason`.

## Migration impact

Code: `errors.go` (mới), `client.go`, `metrics.go`; `go.mod` đưa `genproto/googleapis/rpc` thành dependency trực tiếp.

## Verification

`errors_test.go`: danh tính bị từ chối không retry và không mở breaker; `UNAVAILABLE` retryable và mở breaker; `INTERNAL_ERROR` không retry nhưng vẫn đếm; ErrorInfo của domain khác bị bỏ qua; bảng mặc định theo mã.
